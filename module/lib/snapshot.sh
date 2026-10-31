# shellcheck shell=sh disable=SC3043,SC2012
# Cold image/state snapshots and purge. Never snapshot a running holder.

dsh_snapshot_name_valid() {
  case ${1:-} in
  '' | *[!A-Za-z0-9._-]*) return 1 ;;
  *) return 0 ;;
  esac
}

dsh_sha256() {
  local _out _hash
  _out=$(sha256sum "$1" 2>/dev/null) || return 1
  _hash=$(printf '%s\n' "$_out" | awk 'NR == 1 { print $1 }')
  if [ "${#_hash}" -ne 64 ]; then return 1; fi
  case $_hash in
  *[!0-9a-f]*) return 1 ;;
  esac
  printf '%s\n' "$_hash"
}

dsh_cp_sparse() {
  local _src="$1" _dst="$2" _cp _src_size _dst_size
  [ -f "$_src" ] && [ "$_src" != "$_dst" ] || return 1
  _cp=$(dsh_tool_path cp || true)
  if [ -z "$_cp" ] || ! "$_cp" --help 2>&1 | grep -q -- '--sparse=always'; then
    dsh_err "cp --sparse=always is required"
    return 1
  fi
  "$_cp" --sparse=always "$_src" "$_dst" || return 1
  _src_size=$(dsh_file_size "$_src") || return 1
  _dst_size=$(dsh_file_size "$_dst") || return 1
  [ "$_src_size" = "$_dst_size" ] || {
    dsh_err "sparse copy size mismatch: $_src -> $_dst"
    return 1
  }
}

dsh_snapshot_paths() {
  SNAP_NAME="$1"
  SNAP_IMAGE=$DSH_IMAGE_SNAPSHOT_DIR/rootfs-$SNAP_NAME.img
  SNAP_STATE=$DSH_SNAPSHOT_DIR/state-$SNAP_NAME.tar.gz
  SNAP_MANIFEST=$DSH_SNAPSHOT_DIR/rootfs-$SNAP_NAME.manifest
}

dsh_image_assert_tree_free() {
  local _img _loop _losetup
  _losetup=$(dsh_tool_path losetup || true)
  [ -n "$_losetup" ] || {
    dsh_err "losetup is required to prove image ownership"
    return 1
  }
  for _img in "$DSH_IMAGE" "$DSH_IMAGE_DIR"/rootfs*.img "$DSH_IMAGE_SNAPSHOT_DIR"/rootfs-*.img; do
    [ -f "$_img" ] || continue
    if ! _loop=$(dsh_loop_attached_image "$_img"); then
      dsh_err "cannot verify loop attachments: $_img"
      return 1
    fi
    if [ -n "$_loop" ]; then
      dsh_err "image is still attached: $_img -> $_loop"
      return 1
    fi
  done
  return 0
}

dsh_snapshot_require_cold() {
  local _pid _ns _survivors _survivor_rc=0
  _pid=$(dsh_read_line "$DSH_PID_FILE" 2>/dev/null || true)
  if dsh_is_uint "$_pid" && dsh_proc_alive "$_pid"; then
    dsh_err "holder is running (pid $_pid); stop before snapshot/restore"
    return 1
  fi
  _ns=$(dsh_read_line "$DSH_NS_FILE" 2>/dev/null || true)
  if [ -n "$_ns" ]; then
    dsh_recovery_ns_valid "$_ns" || {
      dsh_err "invalid recorded namespace; recover before snapshot/restore"
      return 1
    }
    if [ "${DSH_TEST_SKIP_NS_SCAN:-0}" != 1 ]; then
      _survivors=$(dsh_proc_scan_rootfs_ns "$_ns" 2>/dev/null || true)
      [ -z "$_survivors" ] || {
        dsh_err "namespace survivors remain; recover before snapshot/restore"
        return 1
      }
    fi
  fi
  if mountpoint -q "$DSH_ROOTFS_DIR" 2>/dev/null; then
    dsh_err "rootfs is still mounted: $DSH_ROOTFS_DIR"
    return 1
  fi
  _survivors=$(dsh_proc_any_rootfs "$DSH_ROOTFS_DIR") || _survivor_rc=$?
  case ${_survivor_rc:-0} in
  0)
    dsh_err "rootfs remains in process namespaces: $_survivors"
    return 1
    ;;
  2)
    dsh_err "cannot verify process mount namespaces before snapshot/restore"
    return 1
    ;;
  esac
  dsh_image_assert_tree_free || return 1
  return 0
}

dsh_snapshot_require_space() {
  local _used _state_used _free
  _used=$(du -sk "$1" 2>/dev/null | awk 'NR == 1 { print $1 }')
  _state_used=$(du -sk "$2" 2>/dev/null | awk 'NR == 1 { print $1 }')
  _free=$(df -Pk "$DSH_IMAGE_DIR" 2>/dev/null | awk 'NR == 2 { print $4 }')
  if ! dsh_is_uint "$_used" || ! dsh_is_uint "${_state_used:-0}" || ! dsh_is_uint "$_free"; then
    dsh_err "cannot determine snapshot space requirements"
    return 1
  fi
  if [ "$_free" -lt "$((_used + ${_state_used:-0} + 65536))" ]; then
    dsh_err "insufficient free space for a sparse image/state snapshot"
    return 1
  fi
  return 0
}

dsh_snapshot_validate_archive() {
  tar -tzf "$1" >/dev/null 2>&1 || {
    dsh_err "state archive is unreadable: $1"
    return 1
  }
  tar -tvzf "$1" 2>/dev/null | awk '
    function normalize(path,    n, i, a, out) {
      n = split(path, a, "/")
      out = ""
      for (i = 1; i <= n; i++) {
        if (a[i] == "" || a[i] == ".") continue
        if (a[i] == "..") {
          if (out == "") return ""
          if (index(out, "/")) sub(/\/[^\/]*$/, "", out)
          else out = ""
          continue
        }
        if (out == "") out = a[i]
        else out = out "/" a[i]
      }
      return out
    }
    NF < 6 { bad = 1; next }
    {
      type = substr($1, 1, 1)
      $1 = $2 = $3 = $4 = $5 = ""
      sub(/^ +/, "", $0)
      target = ""
      if (type == "l" || type == "h") {
        if (index($0, " -> ")) {
          target = $0
          sub(/^.* -> /, "", target)
          sub(/ -> .*$/, "", $0)
        } else if (index($0, " link to ")) {
          target = $0
          sub(/^.* link to /, "", target)
          sub(/ link to .*$/, "", $0)
        }
      }
      if (type == "l" && target == "") bad = 1
      if (target != "") {
        if (target ~ /^\// || index(target, "\\") != 0) {
          bad = 1
        } else if (type == "h") {
          resolved = normalize(target)
          if (resolved != "state" && resolved !~ /^state\//) bad = 1
        } else {
          dir = $0
          sub(/\/[^\/]*$/, "", dir)
          if (dir == $0) dir = ""
          resolved = normalize(dir "/" target)
          if (resolved != "state" && resolved !~ /^state\//) bad = 1
        }
      }
      if ($0 ~ /^\// || $0 ~ /(^|\/)\.\.(\/|$)/) bad = 1
    }
    END { exit bad }
  ' || {
    dsh_err "state archive contains unsafe paths"
    return 1
  }
  return 0
}

dsh_snapshot_fsck_clean() {
  local _e2fsck
  _e2fsck=$(dsh_tool_path e2fsck || true)
  [ -n "$_e2fsck" ] || {
    dsh_err "e2fsck is required for snapshot validation"
    return 1
  }
  "$_e2fsck" -fn "$1" >/dev/null 2>&1 || {
    dsh_err "snapshot image is not a clean ext4 filesystem: $1"
    return 1
  }
  return 0
}

dsh_snapshot_verify() {
  local _name="$1" _want _state_want _got _state_got _manifest_image _manifest_name
  dsh_snapshot_name_valid "$_name" || return 2
  dsh_snapshot_paths "$_name"
  [ -f "$SNAP_IMAGE" ] && [ -f "$SNAP_STATE" ] && [ -f "$SNAP_MANIFEST" ] || {
    dsh_err "snapshot is incomplete: $_name"
    return 1
  }
  _want=$(sed -n 's/^sha256=//p' "$SNAP_MANIFEST" | head -n 1)
  _state_want=$(sed -n 's/^state_sha256=//p' "$SNAP_MANIFEST" | head -n 1)
  _manifest_image=$(sed -n 's/^image=//p' "$SNAP_MANIFEST" | head -n 1)
  _manifest_name=$(sed -n 's/^name=//p' "$SNAP_MANIFEST" | head -n 1)
  [ "$_manifest_image" = "$SNAP_IMAGE" ] && [ "$_manifest_name" = "$_name" ] || {
    dsh_err "snapshot manifest identity mismatch: $_name"
    return 1
  }
  [ -n "$_want" ] && [ -n "$_state_want" ] || {
    dsh_err "snapshot manifest lacks required hashes: $_name"
    return 1
  }
  _got=$(dsh_sha256 "$SNAP_IMAGE") || return 1
  _state_got=$(dsh_sha256 "$SNAP_STATE") || return 1
  [ "$_want" = "$_got" ] && [ "$_state_want" = "$_state_got" ] || {
    dsh_err "snapshot checksum mismatch: $_name"
    return 1
  }
  dsh_snapshot_validate_archive "$SNAP_STATE" || return 1
  dsh_snapshot_fsck_clean "$SNAP_IMAGE" || return 1
  return 0
}

dsh_snapshot_mark_good() {
  dsh_snapshot_name_valid "$1" || return 2
  dsh_state_write_atomic "$DSH_LAST_GOOD_FILE" "$1" || return 1
  rm -f "$DSH_CTL_DIR/boot-rollback-attempted"
}

dsh_snapshot_prune() {
  local _list _old _base _stem _good
  _good=$(dsh_read_line "$DSH_LAST_GOOD_FILE" 2>/dev/null || true)
  _list=$(ls -1t "$DSH_IMAGE_SNAPSHOT_DIR"/rootfs-*.img 2>/dev/null | tail -n +8)
  [ -n "$_list" ] || return 0
  printf '%s\n' "$_list" | while IFS= read -r _old; do
    [ -n "$_old" ] || continue
    _base=${_old##*/}
    _stem=${_base#rootfs-}
    _stem=${_stem%.img}
    [ "$_stem" = "$_good" ] && continue
    case $_old in
    "$DSH_IMAGE_SNAPSHOT_DIR"/*)
      rm -f "$_old" "$DSH_SNAPSHOT_DIR/state-$_stem.tar.gz" "$DSH_SNAPSHOT_DIR/rootfs-$_stem.manifest"
      ;;
    esac
  done
}

dsh_snapshot_create_locked() {
  local _name="$1" _promote="$2" _image_tmp _state_tmp _manifest_tmp _sha _state_sha
  dsh_snapshot_require_cold || return 1
  dsh_snapshot_paths "$_name"
  [ -f "$DSH_IMAGE" ] || {
    dsh_err "image missing: $DSH_IMAGE"
    return 1
  }
  [ ! -e "$SNAP_IMAGE" ] && [ ! -e "$SNAP_STATE" ] && [ ! -e "$SNAP_MANIFEST" ] || {
    dsh_err "snapshot already exists: $_name"
    return 1
  }
  mkdir -p "$DSH_IMAGE_SNAPSHOT_DIR" "$DSH_SNAPSHOT_DIR" "$DSH_CTL_DIR" || return 1
  dsh_snapshot_require_space "$DSH_IMAGE" "$DSH_STATE_DIR" || return 1
  dsh_e2fsck_prepare "$DSH_IMAGE" || return 1
  _image_tmp=$SNAP_IMAGE.tmp.$$
  _state_tmp=$SNAP_STATE.tmp.$$
  _manifest_tmp=$SNAP_MANIFEST.tmp.$$
  dsh_cp_sparse "$DSH_IMAGE" "$_image_tmp" || {
    rm -f "$_image_tmp"
    return 1
  }
  sync
  _sha=$(dsh_sha256 "$_image_tmp") || {
    rm -f "$_image_tmp"
    return 1
  }
  tar -czf "$_state_tmp" -C "$DSH_ROOT" state || {
    rm -f "$_image_tmp" "$_state_tmp"
    return 1
  }
  _state_sha=$(dsh_sha256 "$_state_tmp") || {
    rm -f "$_image_tmp" "$_state_tmp"
    return 1
  }
  dsh_snapshot_validate_archive "$_state_tmp" || {
    rm -f "$_image_tmp" "$_state_tmp"
    return 1
  }
  dsh_snapshot_fsck_clean "$_image_tmp" || {
    rm -f "$_image_tmp" "$_state_tmp"
    return 1
  }
  {
    printf 'name=%s\n' "$_name"
    printf 'created=%s\n' "$(dsh_timestamp)"
    printf 'image=%s\n' "$SNAP_IMAGE"
    printf 'sha256=%s\n' "$_sha"
    printf 'state=%s\n' "$SNAP_STATE"
    printf 'state_sha256=%s\n' "$_state_sha"
  } >"$_manifest_tmp" || {
    rm -f "$_image_tmp" "$_state_tmp" "$_manifest_tmp"
    return 1
  }
  chmod 0600 "$_image_tmp" "$_state_tmp" "$_manifest_tmp" || {
    rm -f "$_image_tmp" "$_state_tmp" "$_manifest_tmp"
    return 1
  }
  if ! mv "$_image_tmp" "$SNAP_IMAGE" || ! mv "$_state_tmp" "$SNAP_STATE" || ! mv "$_manifest_tmp" "$SNAP_MANIFEST"; then
    rm -f "$_image_tmp" "$_state_tmp" "$_manifest_tmp" "$SNAP_IMAGE" "$SNAP_STATE" "$SNAP_MANIFEST"
    dsh_err "snapshot publication failed; partial files were removed"
    return 1
  fi
  if [ "$_promote" -eq 1 ]; then
    dsh_snapshot_mark_good "$_name" || return 1
  fi
  dsh_snapshot_prune
  printf 'snapshot: %s\nsha256: %s\n' "$SNAP_IMAGE" "$_sha"
  return 0
}

dsh_snapshot_create() {
  local _name='' _promote=0 _arg _rc
  for _arg in "$@"; do
    case $_arg in
    --last-good) _promote=1 ;;
    -*)
      dsh_err "unknown snapshot option: $_arg"
      return 2
      ;;
    *)
      [ -z "$_name" ] || {
        dsh_err "snapshot create accepts one name"
        return 2
      }
      _name=$_arg
      ;;
    esac
  done
  if [ -z "$_name" ]; then _name=$(date '+%Y%m%d-%H%M%S' 2>/dev/null || date +%s); fi
  dsh_snapshot_name_valid "$_name" || {
    dsh_err "invalid snapshot name: $_name"
    return 2
  }
  if [ "${DSH_IMAGE_CLAIM_ACTIVE:-0}" = 1 ]; then
    dsh_snapshot_create_claimed "$_name" "$_promote"
  else
    dsh_image_claim_exec snapshot-create "$_name" "$_promote"
  fi
}

dsh_snapshot_create_claimed() {
  local _rc
  dsh_lock_acquire || return 1
  dsh_snapshot_create_locked "$1" "$2"
  _rc=$?
  dsh_lock_release
  return "$_rc"
}

dsh_snapshot_promote() {
  local _name="${1:-}" _rc
  dsh_snapshot_name_valid "$_name" || {
    dsh_err "snapshot promote requires a valid name"
    return 2
  }
  dsh_lock_acquire || return 1
  dsh_snapshot_require_cold && dsh_snapshot_verify "$_name" && dsh_snapshot_mark_good "$_name"
  _rc=$?
  dsh_lock_release
  [ "$_rc" -eq 0 ] && printf 'last-good: %s\n' "$_name"
  return "$_rc"
}

dsh_snapshot_list() {
  _json=0
  case ${1:-} in
  --json) _json=1 ;;
  esac
  _good=$(dsh_read_line "$DSH_LAST_GOOD_FILE" 2>/dev/null || true)
  if [ "$_json" -eq 1 ]; then
    printf '{"last_good":"%s","snapshots":[' "$(dsh_json_escape "$_good")"
    _first=1
    for _img in "$DSH_IMAGE_SNAPSHOT_DIR"/rootfs-*.img; do
      [ -f "$_img" ] || continue
      _name=${_img##*/rootfs-}
      _name=${_name%.img}
      _bytes=$(stat -c %s "$_img" 2>/dev/null || printf 0)
      _mtime=$(date -r "$_img" '+%Y-%m-%dT%H:%M:%S%z' 2>/dev/null || true)
      [ "$_first" -eq 1 ] || printf ','
      _first=0
      printf '{"name":"%s","file":"%s","bytes":%s,"created":"%s","last_good":%s}' \
        "$(dsh_json_escape "$_name")" "$(dsh_json_escape "$_img")" \
        "$(dsh_is_uint "$_bytes" && printf '%s' "$_bytes" || printf 0)" \
        "$(dsh_json_escape "$_mtime")" \
        "$([ "$_name" = "$_good" ] && printf true || printf false)"
    done
    printf ']}\n'
    return 0
  fi
  printf 'image snapshots: %s\n' "$DSH_IMAGE_SNAPSHOT_DIR"
  if [ -d "$DSH_IMAGE_SNAPSHOT_DIR" ]; then
    ls -1t "$DSH_IMAGE_SNAPSHOT_DIR"/rootfs-*.img 2>/dev/null || true
  fi
  printf '\nstate snapshots: %s\n' "$DSH_SNAPSHOT_DIR"
  if [ -d "$DSH_SNAPSHOT_DIR" ]; then
    ls -1t "$DSH_SNAPSHOT_DIR"/state-*.tar.gz 2>/dev/null || true
  fi
  printf 'last-good: %s\n' "${_good:-none}"
}

dsh_snapshot_state_rollback() {
  local _stage="$1" _changed="$2" _ctl_changed="$3" _child _name _rc=0
  for _child in $_changed; do
    rm -rf "${DSH_STATE_DIR:?}/$_child"
    if [ -e "$_stage/old/$_child" ]; then
      mv "$_stage/old/$_child" "$DSH_STATE_DIR/$_child" || _rc=1
    fi
  done
  for _name in $_ctl_changed; do
    if [ -f "$_stage/old/ctl-$_name" ]; then
      cp "$_stage/old/ctl-$_name" "$DSH_CTL_DIR/$_name.rollback.$$" &&
        mv -f "$DSH_CTL_DIR/$_name.rollback.$$" "$DSH_CTL_DIR/$_name" || _rc=1
    else
      rm -f "$DSH_CTL_DIR/$_name"
    fi
  done
  return "$_rc"
}

dsh_snapshot_restore_state() {
  local _archive="$1" _stage="$DSH_ROOT/.state-restore.$$" _child _name _src
  local _changed='' _ctl_changed='' _rc _failed=0
  mkdir "$_stage" || return 1
  mkdir "$_stage/new" "$_stage/old" || {
    rm -rf "$_stage"
    return 1
  }
  if ! tar -xzf "$_archive" -C "$_stage/new"; then
    rm -rf "$_stage"
    return 1
  fi
  if [ ! -e "$_stage/new/state/runtime" ]; then
    mkdir "$_stage/new/state/runtime" || {
      rm -rf "$_stage"
      return 1
    }
  fi
  for _name in url health guard ttyd.pass; do
    if [ ! -e "$_stage/new/state/runtime/$_name" ] && [ -e "$_stage/new/state/guest/$_name" ]; then
      mv "$_stage/new/state/guest/$_name" "$_stage/new/state/runtime/$_name" || {
        rm -rf "$_stage"
        return 1
      }
    fi
  done
  if [ -e "$_stage/new/state/dsh-home/workspace" ] || [ -L "$_stage/new/state/dsh-home/workspace" ]; then
    if [ -e "$_stage/new/state/workspace" ] || [ -L "$_stage/new/state/workspace" ]; then
      dsh_err "state archive contains both legacy and independent workspaces"
      rm -rf "$_stage"
      return 1
    fi
    if [ ! -d "$_stage/new/state/dsh-home/workspace" ] || [ -L "$_stage/new/state/dsh-home/workspace" ]; then
      dsh_err "legacy linked workspace requires explicit migration"
      rm -rf "$_stage"
      return 1
    fi
    mv "$_stage/new/state/dsh-home/workspace" "$_stage/new/state/workspace" || {
      rm -rf "$_stage"
      return 1
    }
  elif [ ! -e "$_stage/new/state/workspace" ] && [ ! -L "$_stage/new/state/workspace" ]; then
    if ! mkdir "$_stage/new/state/workspace" || ! chmod 0700 "$_stage/new/state/workspace"; then
      rm -rf "$_stage"
      return 1
    fi
  fi
  for _child in dsh-home guest runtime workspace; do
    if [ ! -d "$_stage/new/state/$_child" ] || [ -L "$_stage/new/state/$_child" ]; then
      dsh_err "state archive lacks a real directory: $_child"
      rm -rf "$_stage"
      return 1
    fi
  done
  for _name in launcher.pid launcher.starttime launcher.bootid launcher.ns loopdev url mounts cleanup.status module.json module.sha256; do
    rm -f "$_stage/new/state/guest/$_name"
  done
  rm -f "$_stage/new/state/runtime/url" "$_stage/new/state/runtime/health"
  for _child in dsh-home guest runtime workspace; do
    if [ -e "$DSH_STATE_DIR/$_child" ]; then
      mv "$DSH_STATE_DIR/$_child" "$_stage/old/$_child" || {
        dsh_snapshot_state_rollback "$_stage" "$_changed" "$_ctl_changed"
        _rc=$?
        [ "$_rc" -ne 0 ] || rm -rf "$_stage"
        [ "$_rc" -eq 0 ] && return 1
        return 4
      }
    fi
    _changed="$_changed $_child"
    if ! mv "$_stage/new/state/$_child" "$DSH_STATE_DIR/$_child"; then
      dsh_snapshot_state_rollback "$_stage" "$_changed" "$_ctl_changed"
      _rc=$?
      [ "$_rc" -ne 0 ] || rm -rf "$_stage"
      [ "$_rc" -eq 0 ] && return 1
      return 4
    fi
  done
  for _name in last-good autostart boot-fails install-version system-rw.list; do
    _src=$_stage/new/state/ctl/$_name
    [ -f "$_src" ] && [ ! -L "$_src" ] || continue
    if [ -f "$DSH_CTL_DIR/$_name" ]; then
      cp -p "$DSH_CTL_DIR/$_name" "$_stage/old/ctl-$_name" || {
        _failed=1
        break
      }
    fi
    cp "$_src" "$DSH_CTL_DIR/$_name.restore.$$" || {
      _failed=1
      break
    }
    mv -f "$DSH_CTL_DIR/$_name.restore.$$" "$DSH_CTL_DIR/$_name" || {
      _failed=1
      break
    }
    _ctl_changed="$_ctl_changed $_name"
  done
  if [ "$_failed" -ne 0 ]; then
    rm -f "$DSH_CTL_DIR/$_name.restore.$$"
    dsh_snapshot_state_rollback "$_stage" "$_changed" "$_ctl_changed"
    _rc=$?
    [ "$_rc" -ne 0 ] || rm -rf "$_stage"
    [ "$_rc" -eq 0 ] && return 1
    return 4
  fi
  rm -rf "$_stage"
  return 0
}

dsh_snapshot_restore_locked() {
  local _name="$1" _tmp _want _previous _previous_hash='' _state_rc
  dsh_snapshot_name_valid "$_name" || return 2
  dsh_step "确认 chroot 已停止"
  dsh_snapshot_require_cold || return 1
  dsh_step "校验快照清单与镜像哈希"
  dsh_snapshot_verify "$_name" || return 1
  dsh_step "检查剩余空间"
  dsh_snapshot_require_space "$SNAP_IMAGE" "$DSH_STATE_DIR" || return 1
  _want=$(sed -n 's/^sha256=//p' "$SNAP_MANIFEST" | head -n 1)
  _tmp=$DSH_IMAGE.restore.$$.tmp
  dsh_cp_sparse "$SNAP_IMAGE" "$_tmp" || {
    rm -f "$_tmp"
    return 1
  }
  [ "$(dsh_sha256 "$_tmp")" = "$_want" ] || {
    rm -f "$_tmp"
    dsh_err "restored temporary image checksum mismatch"
    return 1
  }
  sync
  dsh_step "保留当前镜像作为回滚点"
  _previous=$DSH_IMAGE_DIR/rootfs-pre-restore-$(date +%s)-$$.img
  if [ -f "$DSH_IMAGE" ]; then
    _previous_hash=$(dsh_sha256 "$DSH_IMAGE") || {
      rm -f "$_tmp"
      return 1
    }
    if ! ln "$DSH_IMAGE" "$_previous" 2>/dev/null; then
      dsh_cp_sparse "$DSH_IMAGE" "$_previous" || {
        rm -f "$_tmp"
        return 1
      }
    fi
    [ "$(dsh_sha256 "$_previous")" = "$_previous_hash" ] || {
      rm -f "$_tmp"
      dsh_err "previous-image backup checksum mismatch"
      return 1
    }
  fi
  if ! mv -f "$_tmp" "$DSH_IMAGE"; then
    rm -f "$_tmp" "$_previous"
    return 1
  fi
  dsh_step "替换镜像并恢复 state"
  dsh_snapshot_restore_state "$SNAP_STATE"
  _state_rc=$?
  if [ "$_state_rc" -ne 0 ]; then
    dsh_err "state restore failed; rolling back the image (rc=$_state_rc)"
    if [ -f "$_previous" ]; then
      if [ "$(dsh_sha256 "$_previous")" != "$_previous_hash" ]; then
        dsh_err "rollback image checksum mismatch; current image was not overwritten"
        dsh_state_update state=recovery-required note=previous-image-checksum-mismatch
        return 4
      fi
      mv -f "$_previous" "$DSH_IMAGE" || _state_rc=4
    else
      rm -f "$DSH_IMAGE"
    fi
    if [ "$_state_rc" -eq 4 ]; then
      dsh_state_update state=recovery-required note=state-rollback-incomplete
      return 4
    fi
    dsh_state_update state=failed note=state-restore-failed
    return 1
  fi
  dsh_clear_runtime_files || return 1
  dsh_recovery_clear || return 1
  dsh_image_backup_prune
  dsh_state_update state=stopped "note=restored:${_name}" url= "restored=$(dsh_timestamp)"
  printf 'restored image: %s\nprevious image kept: %s\n' "$DSH_IMAGE" "${_previous:-none}"
  return 0
}

dsh_snapshot_restore_claimed() {
  local _rc
  dsh_lock_acquire || return 1
  dsh_snapshot_restore_locked "$1"
  _rc=$?
  dsh_lock_release
  return "$_rc"
}

dsh_snapshot_restore() {
  local _name='' _apply=0 _arg
  for _arg in "$@"; do
    case $_arg in
    --apply) _apply=1 ;;
    -h | --help)
      printf 'Usage: dsh-ctl snapshot restore NAME --apply\n'
      return 0
      ;;
    -*) return 2 ;;
    *)
      [ -z "$_name" ] || return 2
      _name=$_arg
      ;;
    esac
  done
  dsh_snapshot_name_valid "$_name" || {
    dsh_err "restore requires a valid snapshot name"
    return 2
  }
  [ "$_apply" -eq 1 ] || {
    dsh_err "snapshot restore replaces the cold image and state; use --apply"
    return 3
  }
  if [ "${DSH_IMAGE_CLAIM_ACTIVE:-0}" = 1 ]; then
    dsh_snapshot_restore_claimed "$_name"
  else
    dsh_image_claim_exec snapshot-restore "$_name"
  fi
}

# 删除单个快照：只删三件套（镜像、state、manifest），不碰 current 与 last-good。
# last-good 指向的快照必须显式换掉（promote 别的），否则"恢复上一版"会突然失效。
dsh_snapshot_delete_locked() {
  local _name="$1" _good
  dsh_snapshot_paths "$_name"
  dsh_snapshot_require_cold || return 1
  if [ ! -e "$SNAP_IMAGE" ] && [ ! -e "$SNAP_STATE" ] && [ ! -e "$SNAP_MANIFEST" ]; then
    dsh_err "snapshot not found: $_name"
    return 1
  fi
  _good=$(dsh_read_line "$DSH_LAST_GOOD_FILE" 2>/dev/null || true)
  if [ "$_name" = "$_good" ]; then
    dsh_err "snapshot $_name is last-good; promote another snapshot or use prune"
    return 1
  fi
  dsh_step "删除快照 $_name"
  rm -f "$SNAP_IMAGE" "$SNAP_STATE" "$SNAP_MANIFEST" || {
    dsh_err "cannot remove snapshot files: $_name"
    return 1
  }
  dsh_log "snapshot: deleted $_name"
}

dsh_snapshot_delete() {
  local _name='' _apply=0 _arg _rc
  for _arg in "$@"; do
    case $_arg in
    --apply) _apply=1 ;;
    -h | --help)
      printf 'Usage: dsh-ctl snapshot delete NAME --apply\n'
      return 0
      ;;
    -*) return 2 ;;
    *)
      [ -z "$_name" ] || return 2
      _name=$_arg
      ;;
    esac
  done
  dsh_snapshot_name_valid "$_name" || {
    dsh_err "snapshot delete requires a valid snapshot name"
    return 2
  }
  [ "$_apply" -eq 1 ] || {
    dsh_err "snapshot delete removes a rollback point; use --apply"
    return 3
  }
  dsh_lock_acquire || return 1
  dsh_snapshot_delete_locked "$_name"
  _rc=$?
  dsh_lock_release
  [ "$_rc" -eq 0 ] && printf 'deleted: %s\n' "$_name"
  return "$_rc"
}

dsh_snapshot_restore_last_good() {
  local _name
  _name=$(dsh_read_line "$DSH_LAST_GOOD_FILE" 2>/dev/null || true)
  dsh_snapshot_name_valid "$_name" || {
    dsh_err "no valid last-good snapshot; use snapshot create NAME --last-good or snapshot promote NAME"
    return 1
  }
  dsh_snapshot_restore "$_name" --apply
}

dsh_purge_guard() {
  if [ "${DSH_TEST_ALLOW_UNUSUAL_PATHS:-0}" = 1 ]; then
    return 0
  fi
  case $DSH_ROOT in
  /data/adb/chroot) ;;
  *)
    dsh_err "refusing purge outside /data/adb/chroot: $DSH_ROOT"
    return 1
    ;;
  esac
  case $DSH_IMAGE_DIR in
  /data/media/0/ubuntu-rootfs) ;;
  *)
    dsh_err "refusing purge outside /data/media/0/ubuntu-rootfs: $DSH_IMAGE_DIR"
    return 1
    ;;
  esac
}

dsh_purge() {
  local _yes=0 _keep=0 _arg _rc=0
  for _arg in "$@"; do
    case $_arg in
    --yes) _yes=1 ;;
    --keep-image) _keep=1 ;;
    -h | --help)
      printf 'Usage: dsh-ctl purge --yes [--keep-image]\n'
      return 0
      ;;
    *)
      dsh_err "unknown purge option: $_arg"
      return 2
      ;;
    esac
  done
  [ "$_yes" -eq 1 ] || {
    dsh_err "purge requires --yes"
    return 2
  }
  dsh_purge_guard || return 1
  if [ "${DSH_IMAGE_CLAIM_ACTIVE:-0}" != 1 ]; then
    if [ "$_keep" -eq 1 ]; then
      dsh_image_claim_exec purge --yes --keep-image
    else
      dsh_image_claim_exec purge --yes
    fi
    return $?
  fi
  dsh_purge_claimed "$_keep"
}

dsh_purge_claimed() {
  local _keep="$1" _rc=0 _was_disabled=0
  [ ! -e "$DSH_WATCHDOG_DISABLED" ] || _was_disabled=1
  dsh_lock_acquire || return 1
  printf '1\n' >"$DSH_PURGE_MARKER" || {
    dsh_lock_release
    return 1
  }
  dsh_lock_release || return 1
  if ! "$DSH_WATCHDOG" --stop --disable; then
    dsh_purge_restore_watchdog "$_was_disabled"
    dsh_err "watchdog did not stop; refusing purge"
    return 1
  fi
  dsh_lock_acquire || {
    dsh_purge_restore_watchdog "$_was_disabled"
    return 1
  }
  if ! dsh_stop_locked; then
    dsh_err "stop failed; refusing purge"
    _rc=1
  elif ! dsh_snapshot_require_cold; then
    dsh_err "live mounts or attachments remain; refusing purge"
    _rc=1
  elif [ "$_keep" -eq 0 ] && ! rm -rf "$DSH_IMAGE_DIR"; then
    dsh_err "cannot remove $DSH_IMAGE_DIR"
    _rc=1
  elif ! rm -rf "$DSH_ROOT"; then
    dsh_err "cannot remove $DSH_ROOT"
    _rc=1
  fi
  dsh_lock_release || _rc=1
  if [ "$_rc" -ne 0 ]; then
    dsh_purge_restore_watchdog "$_was_disabled"
    return 1
  fi
  printf 'purged: %s%s\n' "$DSH_ROOT" "$([ "$_keep" -eq 1 ] && printf ' (image kept)' || printf ' + image dir')"
}

dsh_purge_restore_watchdog() {
  local _was_disabled="$1"
  [ -d "$DSH_CTL_DIR" ] || {
    dsh_err "data root was already removed; cannot restore watchdog state"
    return 1
  }
  if [ "$_was_disabled" -eq 1 ]; then
    rm -f "$DSH_PURGE_MARKER" || {
      dsh_err "cannot clear the purge marker"
      return 1
    }
  else
    "$DSH_WATCHDOG" --enable || {
      dsh_err "cannot restore watchdog after purge"
      return 1
    }
  fi
}

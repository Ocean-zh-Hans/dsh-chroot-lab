# DSH Android environment inside the chroot.
# Keep this file POSIX-compatible; it is sourced by login shells.

export ANDROID_DATA=/data
export ANDROID_ROOT=/system
export ANDROID_ART_ROOT=/apex/com.android.art
export ANDROID_I18N_ROOT=/apex/com.android.i18n
export ANDROID_TZDATA_ROOT=/apex/com.android.tzdata
export PATH="$PATH:/system/bin:/system/xbin:/vendor/bin:/product/bin:/system_ext/bin:/odm/bin"

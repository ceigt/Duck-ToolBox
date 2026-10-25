#!/system/bin/sh
# One sleeping worker; no network outside a due renewal/pending verification.
set -eu
umask 077
MODPATH=${0%/bin/*}
DATA_ROOT=${DUCK_TOOLBOX_DATA_ROOT:-/data/adb/duck-toolbox}
STATE="$DATA_ROOT/var/renewal"
for busybox in /data/adb/ksu/bin/busybox /data/adb/ap/bin/busybox /data/adb/magisk/busybox; do
  if [ -x "$busybox" ] && [ -z "${DUCK_RENEWAL_ASH:-}" ]; then
    export DUCK_RENEWAL_ASH=1 ASH_STANDALONE=1
    exec "$busybox" sh "$0" "$@"
  fi
done
mkdir -p "$STATE"
chmod 700 "$STATE"
exec 9>"$STATE/scheduler.lock"
flock -n 9 || exit 0
next_check=0
last_wall=0
while [ -d "$MODPATH" ] && [ -d "$STATE" ] && [ ! -e "$MODPATH/disable" ] && [ ! -e "$MODPATH/remove" ]; do
  now=$(date +%s)
  if [ "$now" -ge "$next_check" ] || [ "$now" -lt "$last_wall" ]; then
    "$MODPATH/bin/duckctl.sh" renewal tick >/dev/null 2>&1 || true
    next_check=$((now + 21600))
  fi
  [ -f "$STATE/enabled" ] || break
  last_wall=$now
  # No wakelock. A short sleep bounds the delay after Android resumes from suspend;
  # the wall-clock gate still makes certificate checks six-hourly while awake.
  sleep 300
done

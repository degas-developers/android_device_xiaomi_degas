#!/vendor/bin/sh
# TEMPORARY bring-up diagnostics: periodically dump boot state to /metadata,
# which is unencrypted and readable from recovery.
DIR=/metadata/diag
mkdir -p "$DIR"
i=0
while [ $i -lt 10 ]; do
    sleep 60
    i=$((i + 1))
    {
        echo "=== iter $i uptime: $(cat /proc/uptime) ==="
        echo "--- getprop (svc) ---"
        getprop | grep -E "init\.svc\.|sys\.boot|selinux"
        echo "--- ps ---"
        ps -A -o PID,STAT,NAME
        echo "--- init service events ---"
        dmesg | grep -aE "init:.*(exited|Killing|signal|cannot|Could not)" | tail -60
        echo "--- dmesg tail ---"
        dmesg | tail -300
    } > "$DIR/diag-$i.txt" 2>&1
    /system/bin/logcat -d -b all > "$DIR/logcat-$i.txt" 2>&1
    /system/bin/logcat -d -b crash > "$DIR/crash-$i.txt" 2>&1
    sync
done

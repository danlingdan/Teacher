#!/usr/bin/env bash
set -euo pipefail

language="$1"
source_path="$2"
input_path="$3"
wall_seconds="$4"
cpu_seconds="$5"
memory_bytes="$6"
workspace_bytes="$7"
file_limit="$8"
process_limit="$9"

sandbox_root="$(mktemp -d /tmp/sqlteacher-runner.XXXXXX)"
cleanup() {
    chmod -R u+w "$sandbox_root" 2>/dev/null || true
    rm -rf -- "$sandbox_root"
}
trap cleanup EXIT INT TERM HUP

unshare --user --map-root-user --mount --pid --net --fork /bin/bash -s -- \
    "$sandbox_root" "$language" "$source_path" "$input_path" "$wall_seconds" \
    "$cpu_seconds" "$memory_bytes" "$workspace_bytes" "$file_limit" "$process_limit" <<'SANDBOX'
set -euo pipefail

root="$1"
language="$2"
source_path="$3"
input_path="$4"
wall_seconds="$5"
cpu_seconds="$6"
memory_bytes="$7"
workspace_bytes="$8"
file_limit="$9"
process_limit="${10}"
inode_limit=$((file_limit + 48))

mount --make-rprivate /
mount -t tmpfs -o "size=${workspace_bytes},nr_inodes=${inode_limit},mode=0755" tmpfs "$root"
mkdir -p "$root/usr" "$root/work" "$root/tmp" "$root/proc" "$root/dev" "$root/etc"
mount --rbind /usr "$root/usr"
mount -o remount,ro,bind "$root/usr"
ln -s usr/bin "$root/bin"
ln -s usr/lib "$root/lib"
if [[ -d /usr/lib64 ]]; then ln -s usr/lib64 "$root/lib64"; fi
touch "$root/dev/null" "$root/dev/urandom"
mount --bind /dev/null "$root/dev/null"
mount --bind /dev/urandom "$root/dev/urandom"
mount -t proc -o nosuid,nodev,noexec proc "$root/proc"
if [[ -f /etc/ld.so.cache ]]; then cp /etc/ld.so.cache "$root/etc/ld.so.cache"; fi
printf 'sandbox:x:65534:65534:SQLTeacher Runner:/nonexistent:/usr/sbin/nologin\n' > "$root/etc/passwd"
printf 'sandbox:x:65534:\n' > "$root/etc/group"
cp -- "$source_path" "$root/work/source"
cp -- "$input_path" "$root/work/input"

chroot "$root" /usr/bin/env -i \
    PATH=/usr/bin:/bin HOME=/nonexistent TMPDIR=/tmp LANG=C.UTF-8 LC_ALL=C.UTF-8 \
    /bin/bash -s -- "$language" "$wall_seconds" "$cpu_seconds" "$memory_bytes" "$process_limit" <<'WORKLOAD'
set -euo pipefail
language="$1"
wall_seconds="$2"
cpu_seconds="$3"
memory_bytes="$4"
process_limit="$5"
ulimit -c 0
ulimit -n 64
cd /work

limited() {
    setpriv --no-new-privs --bounding-set=-all --inh-caps=-all --ambient-caps=-all \
        prlimit --cpu="$cpu_seconds" --as="$memory_bytes" \
        --fsize=8388608 -- "$@"
}

case "$language" in
    JAVA)
        command -v javac >/dev/null 2>&1 && command -v java >/dev/null 2>&1 || exit 20
        mv source Main.java
        limited javac -encoding UTF-8 -d /work Main.java || exit 10
        runtime=(java "-Xmx$((memory_bytes / 2))" -XX:ActiveProcessorCount=1 -cp /work Main)
        ;;
    PYTHON)
        command -v python3 >/dev/null 2>&1 || exit 20
        mv source main.py
        runtime=(python3 -I -B /work/main.py)
        ;;
    C)
        command -v gcc >/dev/null 2>&1 || exit 20
        mv source main.c
        limited gcc -std=c17 -O0 -pipe -Wall -Wextra -o /work/program main.c || exit 10
        runtime=(/work/program)
        ;;
    CPP)
        command -v g++ >/dev/null 2>&1 || exit 20
        mv source main.cpp
        limited g++ -std=c++20 -O0 -pipe -Wall -Wextra -o /work/program main.cpp || exit 10
        runtime=(/work/program)
        ;;
    *)
        exit 21
        ;;
esac

set +e
limited timeout --signal=TERM --kill-after=1 "${wall_seconds}s" "${runtime[@]}" < /work/input
status=$?
set -e
if [[ $status -eq 124 || $status -eq 137 || $status -eq 143 ]]; then exit 12; fi
if [[ $status -ne 0 ]]; then exit 11; fi
WORKLOAD
SANDBOX

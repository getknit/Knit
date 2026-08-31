#!/usr/bin/env bash
#
# Give an emulator a REAL Bluetooth radio, so it can join the actual Knit mesh over BLE.
#
# The emulator's built-in Bluetooth is netsim/rootcanal — a pure *simulation*. Two emulators on one host
# can see each other through it; a physical phone never can, because no packet ever reaches the air. The
# only route to real RF is USB passthrough of a host Bluetooth dongle
# (https://source.android.com/docs/automotive/start/passthrough), which works here because the guest's
# `android.hardware.bluetooth-service.default` binds a *kernel* HCI device (mgmt socket + HCI_CHANNEL_USER),
# not a QEMU serial port — so a passed-through controller is a first-class citizen of the guest BT stack.
#
# Four things have to line up, and three of them are not in Google's doc:
#
#   1. The host must hand the dongle over — a udev rule for the node's permissions, and an unbind from the
#      host's own btusb (see `host`, which prints the sudo commands; nothing here needs root itself).
#   2. `-feature -BluetoothEmulation`, or `bt_vhci_forwarder` supplies a virtual controller that shadows
#      the real one.
#   3. A **rootable, full** system image. Play-Store images can't `adb root` (no firmware push, no dmesg);
#      `aosp_atd` roots fine but ships no SystemUI and no launcher, so its window is permanently black and
#      it can only be driven by instrumentation. `google_apis` is the one that is both.
#   4. Realtek firmware inside the guest. The SDK images carry btusb/btrtl but no `rtl_bt/*`, and QEMU's
#      attach resets the chip to ROM (`lmp_subver=8761`), so the host's patch does not carry over. Without
#      it `hci_dev_open` fails, the controller is never announced on the mgmt socket, and the HAL sits on
#      "waiting for hci interface 0" forever — which is what a wedge here looks like from logcat.
#
# `bootstrap` is once per AVD (the firmware lands in the overlayfs upper dir and survives reboots).
# `setup` is once per boot: the firmware search path, SELinux, and the driver rebind all reset, and the
# rebind is what finally opens the controller and runs the firmware download.
#
# BLE only — there is no Wi-Fi Aware in an emulator, so it joins as a BLE-only node.
#
# Usage:
#   scripts/emulator-ble-mesh.sh host        # check/report host prerequisites (prints sudo commands)
#   scripts/emulator-ble-mesh.sh up          # launch the AVD with passthrough, then run setup
#   scripts/emulator-ble-mesh.sh bootstrap   # once per AVD: push rtl_bt firmware into /vendor/firmware
#   scripts/emulator-ble-mesh.sh setup       # once per boot: fw path + rebind + HAL restart + BT on
#   scripts/emulator-ble-mesh.sh status      # hci wiring, adapter address, Knit mesh state
#   scripts/emulator-ble-mesh.sh down        # kill the emulator, hand the dongle back to the host
#
# Env: KNIT_BLE_AVD (Knit_Mesh_BT), KNIT_BLE_PORT (5580), KNIT_BLE_VID/PID (Edimax BT-8500 RTL8761BU),
#      KNIT_BLE_HEADLESS=1 for -no-window.

set -euo pipefail

AVD=${KNIT_BLE_AVD:-Knit_Mesh_BT}
PORT=${KNIT_BLE_PORT:-5580}
SERIAL="emulator-$PORT"
VID=${KNIT_BLE_VID:-0x7392}
PID=${KNIT_BLE_PID:-0xc611}
SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}
EMULATOR="$SDK/emulator/emulator"
ADB="$SDK/platform-tools/adb"
FW_SRC=${KNIT_BLE_FW_DIR:-/lib/firmware/rtl_bt}
FW_FILES=(rtl8761bu_fw.bin rtl8761bu_config.bin)
GUEST_FW=/vendor/firmware/rtl_bt

say() { printf '\n\033[1m%s\033[0m\n' "$*"; }
die() { printf 'error: %s\n' "$*" >&2; exit 1; }
adb_() { "$ADB" -s "$SERIAL" "$@"; }

# The lsusb bus/device pair for the dongle, as "BUS DEV" (empty if it is not plugged in).
usb_addr() {
  lsusb 2>/dev/null | awk -v id="${VID#0x}:${PID#0x}" '$6 == id { gsub(":", "", $4); print $2, $4; exit }'
}

wait_booted() {
  adb_ wait-for-device
  # shellcheck disable=SC2016  # $(getprop) must expand in the guest shell, not here
  timeout 300 "$ADB" -s "$SERIAL" shell 'while [ "$(getprop sys.boot_completed)" != 1 ]; do sleep 2; done' \
    || die "$SERIAL never finished booting"
}

# The guest-side USB interface btusb is bound to (e.g. 1-1:1.0).
guest_btusb_port() {
  adb_ shell 'ls /sys/bus/usb/drivers/btusb/ 2>/dev/null | grep -E "^[0-9]+-" | head -1' | tr -d '\r'
}

cmd_host() {
  local addr; addr=$(usb_addr)
  [ -n "$addr" ] || die "no $VID:$PID on the USB bus — is the dongle plugged in?"
  local bus dev node; read -r bus dev <<<"$addr"; node="/dev/bus/usb/$bus/$dev"
  local ok=0

  if [ -w "$node" ]; then
    echo "ok: $node is writable (QEMU can claim the dongle)"
  else
    ok=1
    echo "MISSING: $node is not writable by $USER. Install a udev rule (one sudo, persists):"
    echo "  echo 'SUBSYSTEM==\"usb\", ATTR{idVendor}==\"${VID#0x}\", ATTR{idProduct}==\"${PID#0x}\", MODE=\"0666\"' \\"
    echo "    | sudo tee /etc/udev/rules.d/60-emu-bt-passthrough.rules"
    echo "  sudo udevadm control --reload-rules && sudo udevadm trigger --attr-match=idVendor=${VID#0x}"
  fi

  # The host's own btusb must let go, or BlueZ and the guest fight over the same controller.
  local iface="" link
  for link in /sys/bus/usb/drivers/btusb/*-*:*; do
    [ -e "$link" ] || continue
    if [ "$(cat "$link/../idVendor" 2>/dev/null)" = "${VID#0x}" ] &&
       [ "$(cat "$link/../idProduct" 2>/dev/null)" = "${PID#0x}" ]; then
      iface=$(basename "$link"); break
    fi
  done
  if [ -n "$iface" ]; then
    ok=1
    echo "MISSING: the host still holds the dongle on btusb ($iface). Release it (host loses that adapter):"
    echo "  echo $iface | sudo tee /sys/bus/usb/drivers/btusb/unbind"
    echo "  # …and to hand it back later: echo $iface | sudo tee /sys/bus/usb/drivers/btusb/bind"
  else
    echo "ok: the host's btusb is not holding $VID:$PID"
  fi
  return $ok
}

cmd_up() {
  cmd_host || die "host prerequisites are not met (see above)"
  pgrep -f "qemu-system-x86_64.* -avd $AVD" >/dev/null && die "$AVD is already running"
  local window=(); [ -n "${KNIT_BLE_HEADLESS:-}" ] && window=(-no-window)
  local log; log=$(mktemp -t "knit-ble-$AVD-XXXX.log")
  say "launching $AVD on port $PORT (log: $log)"
  nohup "$EMULATOR" -avd "$AVD" -port "$PORT" -no-audio -no-snapshot -writable-system \
    "${window[@]}" -feature -BluetoothEmulation \
    -usb-passthrough "vendorid=$VID,productid=$PID" >"$log" 2>&1 &
  wait_booted
  cmd_setup
}

cmd_bootstrap() {
  local staged; staged=$(mktemp -d)
  for f in "${FW_FILES[@]}"; do
    if [ -f "$FW_SRC/$f" ]; then cp "$FW_SRC/$f" "$staged/"
    elif [ -f "$FW_SRC/$f.zst" ]; then zstd -q -d -f "$FW_SRC/$f.zst" -o "$staged/$f"
    else die "no $f (or $f.zst) under $FW_SRC — install linux-firmware, or set KNIT_BLE_FW_DIR"
    fi
  done

  adb_ root >/dev/null 2>&1 || true; sleep 3; adb_ wait-for-device
  # The first `adb remount` only *arms* overlayfs; /vendor stays read-only until a reboot.
  if adb_ remount 2>&1 | grep -qi 'reboot'; then
    say "rebooting to arm overlayfs"
    adb_ reboot; sleep 5; wait_booted
    adb_ root >/dev/null 2>&1 || true; sleep 3; adb_ wait-for-device
    adb_ remount >/dev/null
  fi

  say "pushing firmware into $GUEST_FW"
  adb_ shell "mkdir -p $GUEST_FW"
  for f in "${FW_FILES[@]}"; do adb_ push "$staged/$f" "$GUEST_FW/" >/dev/null; done
  adb_ shell "chmod 644 $GUEST_FW/*"
  adb_ shell "ls -l $GUEST_FW"
  rm -rf "$staged"
  cmd_setup
}

cmd_setup() {
  adb_ root >/dev/null 2>&1 || true; sleep 3; adb_ wait-for-device
  adb_ shell "[ -f $GUEST_FW/${FW_FILES[0]} ]" || die "no firmware in the guest — run 'bootstrap' first"
  local port; port=$(guest_btusb_port)
  [ -n "$port" ] || die "btusb has no USB interface bound in the guest — did passthrough reach it? (\`up\`)"

  say "arming the controller (fw path, SELinux, rebind $port)"
  # The firmware search path and SELinux mode reset every boot; the rebind is what actually opens the
  # controller (and so runs the firmware download) — without it the HAL waits on hci0 forever.
  # SELinux: the kernel reading vendor_file is denied, and neither firmware_file nor vendor_fw_file
  # exists in this policy, so a label fix is not available — permissive is the lab answer.
  adb_ shell "echo -n /vendor/firmware > /sys/module/firmware_class/parameters/path
              setenforce 0
              echo $port > /sys/bus/usb/drivers/btusb/unbind 2>/dev/null || true
              sleep 1
              echo $port > /sys/bus/usb/drivers/btusb/bind"
  sleep 6
  adb_ shell 'dmesg | grep -E "RTL: (fw version|firmware file)" | tail -2'
  # The HAL is already spinning in waitHciDev by now; restart it so it re-reads the index list.
  adb_ shell 'setprop ctl.restart vendor.bluetooth-default'; sleep 3
  adb_ shell 'svc bluetooth enable' >/dev/null; sleep 10
  cmd_status
}

cmd_status() {
  say "hci wiring"
  # shellcheck disable=SC2016  # runs in the guest shell; $h/$(readlink) expand there
  adb_ shell 'for h in /sys/class/bluetooth/hci*; do
                case "$(readlink $h)" in *usb*) k="USB (passthrough)";; *) k="virtual (netsim/vhci)";; esac
                echo "  $(basename $h): $k"; done'
  say "adapter"
  adb_ shell 'dumpsys bluetooth_manager | sed -n "2,5p"'
  if adb_ shell 'pm path app.getknit.knit' >/dev/null 2>&1; then
    say "knit mesh"
    adb_ shell 'am broadcast -p app.getknit.knit -a app.getknit.knit.debug.STATE' 2>/dev/null |
      sed -n 's/.*data="//;s/"$//p' |
      sed -e 's/,"metrics".*//' -e 's/^/  /'
  fi
}

cmd_down() {
  adb_ emu kill >/dev/null 2>&1 || true
  echo "killed $SERIAL — hand the dongle back to the host with:"
  echo "  echo <iface> | sudo tee /sys/bus/usb/drivers/btusb/bind   # iface e.g. 3-7:1.0, see \`lsusb -t\`"
}

case "${1:-status}" in
  host)      cmd_host ;;
  up)        cmd_up ;;
  bootstrap) cmd_bootstrap ;;
  setup)     cmd_setup ;;
  status)    cmd_status ;;
  down)      cmd_down ;;
  *)         die "unknown command '${1}' — one of: host up bootstrap setup status down" ;;
esac

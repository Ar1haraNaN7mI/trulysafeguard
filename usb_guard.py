#!/usr/bin/env python3
"""通过已授权的 Android 官方 ADB 执行手机「USB 系统防护」中选中的规则。"""
import argparse
import base64
import collections
import contextlib
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import time


PACKAGE = r"[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+"
SELF = "app.local.privacyguard"
URI = "content://app.local.privacyguard.usb/policy"
KEYS = ("sensors", "notifications", "screen")
MODES = {"allow", "deny", "ignore", "default"}
OP = "POST_NOTIFICATION"
NOTIFY_PERMISSION = "android.permission.POST_NOTIFICATIONS"
RESTORABLE_FLAGS = {"USER_SET", "USER_SENSITIVE_WHEN_GRANTED", "USER_SENSITIVE_WHEN_DENIED"}
MAX_RULES = 128


class GuardError(Exception):
    pass


def encode(value):
    raw = json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode("ascii")


def read_rules(output):
    row = re.fullmatch(r"Row: 0 payload=([A-Za-z0-9_-]{1,100000})", output.strip())
    if not row:
        raise GuardError("无法读取唯一的规则行，请确认手机已安装兼容版本。")
    token = row[1]
    try:
        data = json.loads(base64.b64decode(token + "=" * (-len(token) % 4),
                                           altchars=b"-_", validate=True))
    except (ValueError, UnicodeError):
        raise GuardError("手机返回的规则编码无效。") from None
    if not isinstance(data, dict) or type(data.get("v")) is not int or data["v"] != 1:
        raise GuardError("手机与助手的协议版本不同。")
    rules = data.get("rules")
    if not isinstance(rules, list) or len(rules) > MAX_RULES:
        raise GuardError("规则数量或格式无效。")
    seen = set()
    for rule in rules:
        if not isinstance(rule, dict) or not isinstance(rule.get("pkg"), str):
            raise GuardError("应用规则格式无效。")
        pkg = rule["pkg"]
        if (len(pkg) > 255 or not re.fullmatch(PACKAGE, pkg) or pkg in seen
                or any(type(rule.get(key)) is not bool for key in KEYS)):
            raise GuardError("应用包名、开关值或规则唯一性校验失败。")
        seen.add(pkg)
    return rules


def adb_binary(explicit):
    if explicit:
        return explicit
    installed = shutil.which("adb")
    if installed:
        return installed
    filename = "adb.exe" if os.name == "nt" else "adb"
    roots = [os.environ.get("ANDROID_SDK_ROOT"), os.environ.get("ANDROID_HOME")]
    if os.environ.get("LOCALAPPDATA"):
        roots.append(Path(os.environ["LOCALAPPDATA"]) / "Android/Sdk")
    roots += [Path.home() / "Library/Android/sdk", Path.home() / "Android/Sdk"]
    for root in filter(None, roots):
        candidate = Path(root) / "platform-tools" / filename
        if candidate.is_file():
            return str(candidate)
    raise GuardError("找不到 Android SDK platform-tools 的 adb，请用 --adb 指定。")


def run(argv, body=None):
    try:
        # Preserve LF even on Windows when passing a shell command through stdin.
        p = subprocess.run(argv, input=body.encode("utf-8") if body is not None else None,
                           stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=15)
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise GuardError("ADB 连接失败或命令超时。") from exc
    return (p.returncode, p.stdout.decode("utf-8", "replace").strip(),
            p.stderr.decode("utf-8", "replace").strip())


def choose_device(adb, requested):
    code, out, _ = run([adb, "devices", "-l"])
    if code:
        raise GuardError("无法列出 ADB 设备。")
    devices = {}
    for line in out.splitlines():
        fields = line.split()
        if len(fields) >= 2 and fields[1] in {"device", "offline", "unauthorized", "recovery", "sideload"}:
            devices[fields[0]] = fields[1]
    if requested:
        if devices.get(requested) != "device":
            raise GuardError("指定设备未连接、尚未授权或不在正常系统中。")
        return requested
    physical = [serial for serial in devices if not serial.startswith("emulator-")]
    if len(physical) == 1 and devices[physical[0]] == "device":
        return physical[0]
    listed = "、".join(f"{s} ({state})" for s, state in devices.items()) or "无"
    raise GuardError(f"需要用 --serial 指定一台已授权设备。当前设备：{listed}")


class Device:
    def __init__(self, adb, serial):
        self.prefix = [adb, "-s", serial, "shell"]

    def result(self, *args):
        return run(self.prefix + list(args))

    def checked(self, *args):
        code, out, err = self.result(*args)
        if code or err or re.search(r"(?im)^\s*(?:error\b|exception\b|security exception\b|java\.[\w.]*Exception\b|unknown (?:command|operation)\b|permission denial\b)", out):
            raise GuardError("系统拒绝命令、设备断开或当前版本不支持。")
        return out

    def publish(self, rows):
        token = encode({"v": 1, "time": int(time.time()), "status": rows})
        # Through stdin to avoid Windows' command-line length limit with 128 apps.
        # All variable characters are from the URL-safe base64 alphabet.
        line = f"content call --user 0 --uri {URI} --method status --arg {token}\n"
        code, out, err = run(self.prefix + ["-T", "sh"], line)
        if code or err or not re.fullmatch(r"Result: Bundle\[.*\]", out) or "false" in out.lower() or "error" in out.lower():
            raise GuardError("执行状态未能写回手机，请核对手机版本和授权。")


def packages(device, *flags):
    out = device.checked("cmd", "package", "list", "packages", "-U", *flags, "--user", "0")
    result = {}
    for line in out.splitlines():
        match = re.fullmatch(r"package:([^\s:]+)\s+uid:(\d+)", line.strip())
        if not match or match[1] in result:
            raise GuardError("系统应用列表格式未知，已暂停操作。")
        result[match[1]] = int(match[2])
    return result


def ordinary_apps(device):
    apps = packages(device)
    if not apps or SELF not in apps or "android" not in apps:
        raise GuardError("无法核实系统和助手的 UID。")
    third = packages(device, "-3")
    shared = collections.Counter(apps.values())
    return {p: uid for p, uid in third.items() if p != SELF and 10000 <= uid <= 19999
            and apps.get(p) == uid and shared[uid] == 1}


def install_identity(device, pkg, uid):
    # Read only this package's main Package section, never log the dump.
    data = device.checked("dumpsys", "package", pkg)
    section = re.search(r"(?m)^Packages:\s*\n\s+Package \[" + re.escape(pkg) +
                        r"\] [^\n]*:\s*\n((?:[ \t]+[^\n]*\n?|\n)*)", data)
    if not section:
        raise GuardError("无法核实应用安装标识。")
    uid_line = re.search(r"(?m)^\s*(?:userId|appId)=(\d+)\b", section[1])
    installed = re.search(r"(?m)^\s*firstInstallTime=(\d{4}-\d\d-\d\d [\d:]{8})\s*$", section[1])
    if not uid_line or int(uid_line[1]) != uid or not installed:
        raise GuardError("无法核实应用 UID 或初次安装时间。")
    return {"uid": uid, "install": installed[1]}


class Journal:
    def __init__(self, directory, serial):
        directory.mkdir(parents=True, exist_ok=True)
        self.path = directory / (hashlib.sha256(serial.encode("utf-8")).hexdigest() + ".json")
        self.serial = serial
        self.entries = {}

    def load(self):
        if not self.path.exists():
            return
        try:
            data = json.loads(self.path.read_text(encoding="utf-8"))
            if data["v"] != 1 or data["serial"] != self.serial or not isinstance(data["entries"], dict):
                raise ValueError
            for pkg, entry in data["entries"].items():
                if not re.fullmatch(PACKAGE, pkg) or not isinstance(entry, dict):
                    raise ValueError
                if type(entry["uid"]) is not int or not isinstance(entry["install"], str):
                    raise ValueError
                for kind in ("sensors", "notifications"):
                    if kind not in entry:
                        continue
                    item = entry[kind]
                    if item["phase"] not in ("intent", "applied"):
                        raise ValueError
                    if kind == "sensors" and not isinstance(item.get("boot"), str):
                        raise ValueError
                    if kind == "notifications":
                        if item["scope"] == "runtime":
                            if (item["wasGranted"] is not True or not isinstance(item["flags"], list)
                                    or not set(item["flags"]) <= RESTORABLE_FLAGS):
                                raise ValueError
                        elif item["scope"] not in ("uid", "package") or item["old"] not in MODES - {"ignore"}:
                            raise ValueError
            self.entries = data["entries"]
        except (ValueError, KeyError, TypeError, UnicodeError) as exc:
            raise GuardError("本机恢复记录损坏，已暂停写入。") from exc

    def save(self):
        data = {"v": 1, "serial": self.serial, "entries": self.entries}
        with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=self.path.parent,
                                         prefix=self.path.stem + ".", delete=False) as tmp:
            temp_path = Path(tmp.name)
            try:
                json.dump(data, tmp, ensure_ascii=False, separators=(",", ":"))
                tmp.flush()
                os.fsync(tmp.fileno())
            except BaseException:
                tmp.close()
                temp_path.unlink(missing_ok=True)
                raise
        try:
            os.replace(temp_path, self.path)
        finally:
            temp_path.unlink(missing_ok=True)

    def forget(self, pkg, kind):
        row = self.entries[pkg]
        row.pop(kind, None)
        if not any(k in row for k in ("sensors", "notifications")):
            del self.entries[pkg]
        self.save()


@contextlib.contextmanager
def exclusive(path):
    with path.open("a+b") as lock:
        if lock.tell() == 0:
            lock.write(b"0")
            lock.flush()
        lock.seek(0)
        try:
            if os.name == "nt":
                import msvcrt
                msvcrt.locking(lock.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError as exc:
            raise GuardError("已有助手正在管理这台设备。") from exc
        yield


def current_sensor(device, pkg):
    code, out, err = device.result("cmd", "sensorservice", "get-uid-state", pkg, "--user", "0")
    # Android 12 returns dprintf's byte count (idle + newline = 5, active = 7).
    if not err and out in {"idle", "active"} and code in (0, len(out) + 1):
        return out
    raise GuardError("系统未提供可核验的传感器 UID 状态。")


def current_notification(device, pkg):
    output = device.checked("cmd", "appops", "get", "--user", "0", pkg, OP)
    found = {}
    for line in output.splitlines():
        line = line.strip()
        if line == "No operations.":
            continue
        match = re.fullmatch(r"(Uid mode:\s*)?POST_NOTIFICATION:\s*([a-z]+)(?:;.*)?", line)
        if match:
            key = "uid" if match[1] else "package"
            mode = match[2]
        else:
            match = re.fullmatch(r"Default mode:\s*([a-z]+)", line)
            if not match:
                raise GuardError("通知 AppOps 格式未知，已保留系统设置。")
            key, mode = "default", match[1]
        if key in found or mode not in MODES:
            raise GuardError("通知 AppOps 模式未知，已保留系统设置。")
        found[key] = mode
    if not found:
        raise GuardError("无法读取并保存原通知模式。")
    return found


def effective(modes):
    if "uid" in modes:
        return "uid", modes["uid"]
    if "package" in modes:
        return "package", modes["package"]
    return "package", modes["default"]


def scope_mode(modes, scope):
    if scope == "uid":
        return modes.get("uid")
    return modes.get("package", modes.get("default"))


def set_notification(device, pkg, scope, mode):
    flags = ["--uid"] if scope == "uid" else []
    device.checked("cmd", "appops", "set", "--user", "0", *flags, pkg, OP, mode)


def runtime_notification(device, pkg):
    # Work inside the installed user-0 runtime-permissions section. There can
    # be other users and multiple permission sections in a package dump.
    lines = device.checked("dumpsys", "package", pkg).splitlines()
    owner_indent = perm_indent = None
    for line in lines:
        value = line.strip()
        if not value:
            continue
        indent = len(line) - len(line.lstrip())
        if owner_indent is not None and indent <= owner_indent:
            owner_indent = perm_indent = None
        if re.match(r"User 0: .*\binstalled=true\b", value):
            owner_indent = indent
            continue
        if owner_indent is None:
            continue
        if perm_indent is not None and indent <= perm_indent:
            perm_indent = None
        if value == "runtime permissions:":
            perm_indent = indent
            continue
        if perm_indent is None or not value.startswith(NOTIFY_PERMISSION + ":"):
            continue
        match = re.fullmatch(re.escape(NOTIFY_PERMISSION) + r": granted=(true|false), flags=\[([ A-Z_|]*)\]", value)
        if not match:
            break
        flags = sorted({flag.strip() for flag in match[2].split("|") if flag.strip()})
        return match[1] == "true", flags
    raise GuardError("无法确认当前用户的通知运行时授权及标记。")


def active_projection(device):
    code, data, error = device.result("dumpsys", "media_projection")
    lines = [line.strip() for line in data.splitlines() if line.strip()]
    if (code or error or len(lines) < 3 or lines[0] != "MEDIA PROJECTION MANAGER (dumpsys media_projection)"
            or lines[1] != "Media Projection:" or lines.count("Media Projection:") != 1):
        raise GuardError("系统投屏记录格式未知，未停止应用。")
    value = lines[2]
    if value == "null":
        return None
    if value == "Projection:":
        # Newer Android prints current owner identity first, before display details.
        # A consented session can have no virtual display attached.
        head = "\n".join(lines[2:7])
        match = re.fullmatch(r"Projection:\npackageName: (" + PACKAGE + r")\nuid: (\d+)\n"
                             r"userHandle: UserHandle\{0\}\ntype: TYPE_(SCREEN_CAPTURE|MIRRORING|PRESENTATION)",
                             head)
        if match and lines.count("Projection:") == 1 and "privileged: false" in lines:
            return match[1], int(match[2])
        raise GuardError("无法核实当前投屏的用户、UID 或来源类型。")
    match = re.fullmatch(r"\((" + PACKAGE + r"), uid=(\d+)\): TYPE_(SCREEN_CAPTURE|MIRRORING|PRESENTATION)", value)
    if not match:
        raise GuardError("无法确认当前投屏来源，未停止应用。")
    return match[1], int(match[2])


class Controller:
    def __init__(self, device, journal):
        self.d = device
        self.j = journal
        self.last_stop = {}

    def sensors(self, pkg, enabled, identity, boot):
        entry = self.j.entries.get(pkg, {})
        item = entry.get("sensors")
        if item and item["boot"] != boot:
            self.j.forget(pkg, "sensors")  # SensorService's override table is volatile.
            item = None
        if not item and not enabled:
            return "关闭"
        state = current_sensor(self.d, pkg)
        if not enabled:
            if item["phase"] == "intent":
                if state == "idle":
                    return "上次执行未确认，请重新勾选后再关闭以清除覆盖"
            elif state == "idle":
                self.d.checked("cmd", "sensorservice", "reset-uid-state", pkg, "--user", "0")
            self.j.forget(pkg, "sensors")
            return "已解除本助手覆盖"
        if item and item["phase"] == "applied" and state == "idle":
            return "已覆盖，系统报告 idle"
        row = self.j.entries.setdefault(pkg, dict(identity))
        row["sensors"] = {"boot": boot, "phase": "intent"}
        self.j.save()
        self.d.checked("cmd", "sensorservice", "set-uid-state", pkg, "idle", "--user", "0")
        row["sensors"]["phase"] = "applied"
        self.j.save()
        if current_sensor(self.d, pkg) != "idle":
            raise GuardError("命令已执行，系统未报告 idle。")
        return "已覆盖，系统报告 idle"

    def notifications(self, pkg, enabled, identity):
        item = self.j.entries.get(pkg, {}).get("notifications")
        if not enabled and not item:
            return "关闭"
        modes = current_notification(self.d, pkg)
        scope, mode = effective(modes)
        if not enabled:
            if scope_mode(modes, item["scope"]) != "ignore":
                self.j.forget(pkg, "notifications")
                return "已保留系统当前设置"
            set_notification(self.d, pkg, item["scope"], item["old"])
            remaining = current_notification(self.d, pkg)
            restored = scope_mode(remaining, item["scope"])
            if restored is None and item["scope"] == "uid":
                restored = effective(remaining)[1]
            if restored != item["old"]:
                raise GuardError("恢复命令已执行，原通知模式尚未核实。")
            self.j.forget(pkg, "notifications")
            return "已恢复原模式 " + item["old"]
        if mode in {"ignore", "deny"}:
            if item and item["phase"] == "intent" and scope_mode(modes, item["scope"]) == "ignore":
                item["phase"] = "applied"
                self.j.save()
            return "系统已拦截 " + mode
        if item and (scope != item["scope"] or mode != item["old"]):
            return "通知设置已由其他途径更改，已保留"
        if not item:
            row = self.j.entries.setdefault(pkg, dict(identity))
            item = {"scope": scope, "old": mode, "phase": "intent"}
            row["notifications"] = item
            self.j.save()
        set_notification(self.d, pkg, scope, "ignore")
        if effective(current_notification(self.d, pkg))[1] != "ignore":
            raise GuardError("通知命令已执行，系统未确认 ignore。")
        item["phase"] = "applied"
        self.j.save()
        return "系统已拦截 ignore"

    def manage_notifications(self, pkg, enabled, identity, sdk):
        item = self.j.entries.get(pkg, {}).get("notifications")
        if not enabled and not item:
            return "关闭"
        if sdk is None:
            raise GuardError("系统 API 版本未知，已暂停通知操作。")
        if sdk < 33:
            if item and item["scope"] == "runtime":
                raise GuardError("系统版本已变化，无法确认原通知授权。")
            return self.notifications(pkg, enabled, identity)
        if item and item["scope"] != "runtime":
            previous = self.notifications(pkg, False, identity)
            if not enabled:
                return previous
            item = None
        granted, flags = runtime_notification(self.d, pkg)
        if item and item["flags"] != flags:
            if not enabled:
                self.j.forget(pkg, "notifications")
            return "通知授权标记已由其他途径更改，已保留"
        if not enabled:
            if granted:
                self.j.forget(pkg, "notifications")
                return "已保留现有通知授权"
            self.d.checked("pm", "grant", "--user", "0", pkg, NOTIFY_PERMISSION)
            if runtime_notification(self.d, pkg) != (True, flags):
                raise GuardError("通知恢复命令已执行，原授权或标记尚未核实。")
            self.j.forget(pkg, "notifications")
            return "已恢复原通知授权和标记"
        if not granted:
            if item and item["phase"] == "intent":
                item["phase"] = "applied"
                self.j.save()
            return "系统通知授权已关闭"
        if not set(flags) <= RESTORABLE_FLAGS:
            return "通知含固定或未知授权标记，已保留"
        if not item:
            item = {"scope": "runtime", "wasGranted": True, "flags": flags, "phase": "intent"}
            self.j.entries.setdefault(pkg, dict(identity))["notifications"] = item
            self.j.save()
        self.d.checked("pm", "revoke", "--user", "0", pkg, NOTIFY_PERMISSION)
        if runtime_notification(self.d, pkg) != (False, flags):
            raise GuardError("通知撤销命令已执行，实际授权或标记尚未核实。")
        item["phase"] = "applied"
        self.j.save()
        return "系统通知授权已关闭"

    def screen(self, pkg, uid, observed):
        if isinstance(observed, GuardError):
            raise observed
        if observed != (pkg, uid):
            suffix = self.last_stop.get(pkg)
            return "监测中" if not suffix else "监测中，上次已停止 " + suffix
        if active_projection(self.d) != observed:
            return "投屏已变化，本次未停止"
        self.d.checked("am", "force-stop", "--user", "0", pkg)
        if active_projection(self.d) == observed:
            return "已发出强行停止，投屏仍待确认"
        self.last_stop[pkg] = time.strftime("%H:%M:%S")
        return "已停止活动投屏应用 " + self.last_stop[pkg]

    def cycle(self):
        if self.d.checked("am", "get-current-user") != "0":
            raise GuardError("只支持设备当前使用用户 0，已暂停操作。")
        raw = self.d.checked("content", "query", "--user", "0", "--uri", URI, "--projection", "payload")
        rules = read_rules(raw)
        apps = ordinary_apps(self.d) if rules else {}
        sdk = None
        if any(rule["notifications"] or "notifications" in self.j.entries.get(rule["pkg"], {}) for rule in rules):
            try:
                version = self.d.checked("getprop", "ro.build.version.sdk")
                if re.fullmatch(r"[1-9]\d{0,2}", version):
                    sdk = int(version)
            except GuardError:
                pass
        boot = ""
        if any(rule["sensors"] or "sensors" in self.j.entries.get(rule["pkg"], {}) for rule in rules):
            try:
                boot = self.d.checked("cat", "/proc/sys/kernel/random/boot_id")
            except GuardError:
                pass
        observed = None
        if any(rule["screen"] for rule in rules):
            try:
                observed = active_projection(self.d)
            except GuardError as exc:
                observed = exc
        rows = []
        for rule in rules:
            pkg = rule["pkg"]
            row = {"pkg": pkg, **dict.fromkeys(KEYS, "关闭")}
            rows.append(row)
            if not any(rule[k] for k in KEYS) and pkg not in self.j.entries:
                continue
            try:
                if pkg not in apps:
                    raise GuardError("已跳过：系统、共用 UID、自身或未安装应用")
                identity = install_identity(self.d, pkg, apps[pkg])
                prior = self.j.entries.get(pkg)
                if prior and any(prior[k] != v for k, v in identity.items()):
                    raise GuardError("安装身份已变化，已保留系统状态和旧记录")
            except GuardError as exc:
                row.update(dict.fromkeys(KEYS, str(exc)))
                continue
            for key in KEYS:
                try:
                    if key == "sensors":
                        if (rule[key] or "sensors" in self.j.entries.get(pkg, {})) and not re.fullmatch(
                                r"[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}", boot):
                            raise GuardError("无法确认本次开机标识，已暂停传感器操作。")
                        row[key] = self.sensors(pkg, rule[key], identity, boot)
                    elif key == "notifications":
                        row[key] = self.manage_notifications(pkg, rule[key], identity, sdk)
                    elif rule[key]:
                        row[key] = self.screen(pkg, apps[pkg], observed)
                except GuardError as exc:
                    row[key] = str(exc)
        self.d.publish(rows)
        return rows


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, epilog=(
        "先在手机勾选所需应用和功能。传感器限制会影响该应用的正常感应功能。"
        "投屏保护在发现活动投屏后强行停止所选应用，轮询存在延迟。Ctrl+C 结束监测。"
        "需要解除传感器覆盖或恢复通知时，请在手机取消勾选，并保持助手运行至回报。"))
    parser.add_argument("--serial", help="目标设备序列号；多个真机时必填，模拟器须显式选择")
    parser.add_argument("--adb", help="指定 Android 官方 platform-tools 中的 adb")
    parser.add_argument("--once", action="store_true", help="执行一轮并退出")
    parser.add_argument("--interval", type=float, default=2.0, help="两轮之间的间隔秒数，默认为 2")
    state_root = Path(os.environ.get("LOCALAPPDATA") or os.environ.get("XDG_STATE_HOME")
                      or Path.home() / ".local/state") / "PrivacyGuardUSB"
    parser.add_argument("--state-dir", type=Path, default=state_root, help="按设备保存恢复记录的目录")
    args = parser.parse_args(argv)
    if not 0.5 <= args.interval <= 60:
        parser.error("--interval 范围为 0.5 至 60 秒")
    try:
        adb = adb_binary(args.adb)
        serial = choose_device(adb, args.serial)
        journal = Journal(args.state_dir, serial)
        with exclusive(journal.path.with_suffix(".lock")):
            journal.load()
            guardian = Controller(Device(adb, serial), journal)
            print(f"已连接 {serial}。仅执行手机中已选规则。", flush=True)
            previous = None
            while True:
                try:
                    rows = guardian.cycle()
                    message = "\n".join(row["pkg"] + "  " + " / ".join(row[key] for key in KEYS)
                                        for row in rows) or "手机当前没有规则。"
                except GuardError as exc:
                    if args.once:
                        raise
                    message = str(exc)
                if message != previous:
                    print(message, flush=True)
                    previous = message
                if args.once:
                    break
                time.sleep(args.interval)
    except KeyboardInterrupt:
        print("\n已退出。投屏监测结束。取消已应用的限制后请再运行助手，以完成恢复。")
    except (GuardError, OSError) as exc:
        print("助手暂未完成：" + str(exc), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())

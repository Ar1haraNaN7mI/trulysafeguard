"""Start the fixed local rules from the already installed, hash-verified Privacy Guard APK."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import sys
import time

PKG = 'app.local.privacyguard'
URI = 'content://app.local.privacyguard.usb/phone'
STATE = '/data/local/tmp/privacyguard-rules-v1'

def main():
    parser=argparse.ArgumentParser(description='用一次已授权 USB 调试启动手机规则。重启后可重新运行。')
    parser.add_argument('--serial',help='指定 USB 设备序列号')
    parser.add_argument('--apk',type=Path,help='用于核对签名发布件的本地 APK')
    parser.add_argument('--adb',help='Android 官方 adb 路径')
    parser.add_argument('--screen-monitor',choices=('grant','revoke'),help='单独授予或撤回标准投屏监测使用的 DUMP 诊断权限')
    args=parser.parse_args()
    adb=args.adb or shutil.which('adb')
    if not adb:
        sdk=Path(os.environ.get('ANDROID_SDK_ROOT') or os.environ.get('ANDROID_HOME') or Path(os.environ.get('LOCALAPPDATA',''))/'Android/Sdk')
        adb=str(sdk/'platform-tools'/('adb.exe' if os.name=='nt' else 'adb'))
    def run(*parts):
        p=subprocess.run([adb,*parts],capture_output=True,timeout=25)
        out=p.stdout.decode('utf8','replace').strip()
        err=p.stderr.decode('utf8','replace').strip()
        if p.returncode or out.startswith(('Error','Exception')):raise ValueError((err or out or 'ADB 失败')[:350])
        return out
    devices=[line.split()[0] for line in run('devices').splitlines()[1:] if len(line.split())==2 and line.split()[1]=='device']
    if args.serial:
        if args.serial not in devices:raise ValueError('指定设备未连接或调试尚未授权。')
        serial=args.serial
    else:
        physical=[s for s in devices if not s.startswith('emulator-')]
        if len(physical)!=1:raise ValueError('请连接一台已授权手机，多设备时使用 --serial 指定。')
        serial=physical[0]
    def shell(*parts):return run('-s',serial,'shell',shlex.join(parts))
    def status():
        row=shell('content','query','--user','0','--uri',URI,'--projection','payload')
        m=re.fullmatch(r'Row: 0 payload=([A-Za-z0-9_-]+)',row)
        if not m:raise ValueError('请先安装配套的 1.1 或更新版本。')
        return json.loads(base64.urlsafe_b64decode(m[1]+'='*(-len(m[1])%4)))
    if args.apk:digest=hashlib.sha256(args.apk.read_bytes()).hexdigest()
    else:digest=Path(__file__).with_name('APK.sha256').read_text(encoding='utf8').strip()
    if not re.fullmatch('[0-9a-f]{64}',digest):raise ValueError('缺少对应发行版的 APK 校验文件。')
    base=shell('pm','path','--user','0',PKG).splitlines()[0]
    if not re.fullmatch(r'package:/data/app/[A-Za-z0-9_~./+=-]+/base\.apk',base):raise ValueError('手机上未找到预期的安装包。')
    base=base[8:]
    if shell('sha256sum',base).split()[0]!=digest:raise ValueError('已安装 APK 与此激活包版本不一致。请先安装配套 APK。')
    if shell('am','get-current-user')!='0' or not shell('id','-u')=='2000':raise ValueError('需要主用户和普通 ADB 调试授权。')
    if args.screen_monitor:
        permission='android.permission.DUMP'
        shell('pm',args.screen_monitor,'--user','0',PKG,permission)
        report=shell('dumpsys','package',PKG)
        granted=re.findall(r'^\s*android\.permission\.DUMP: granted=(true|false)\b',report,re.M)
        want=args.screen_monitor=='grant'
        if not granted and want or granted and set(granted)!={str(want).lower()}:
            raise ValueError('系统没有确认这次诊断权限变更。')
        print('守护的系统诊断权限已授予。可在“独立投屏监测”页开启。' if want else '守护的系统诊断权限已撤回。')
        return
    shell('mkdir','-p',STATE)
    if shell('stat','-c','%u:%F',STATE)!='2000:directory':raise ValueError('手机工作目录所有者或类型异常。')
    shell('chmod','700',STATE)
    before=status().get('phoneTime',0)
    if 'ok=true' not in shell('content','call','--user','0','--uri',URI,'--method','activatePhone'):raise ValueError('激活请求未被接受。')
    # Only the fixed entry in the verified, installed APK can be launched.
    executable=shlex.join(['/system/bin/setsid','/system/bin/nohup','/system/bin/env','CLASSPATH='+base,
                           '/system/bin/app_process','/system/bin','--nice-name=PrivacyGuardRules','GuardPhone'])
    last=before
    confirmations=0
    for attempt in range(36):
        # An older version may still own the single-instance lock briefly after an APK update.
        if attempt in (0,6,14,23) and confirmations<2:
            run('-s',serial,'shell','umask 077; '+executable+' </dev/null >>'+STATE+'/startup.log 2>&1 &')
        time.sleep(1)
        s=status()
        if s.get('phoneTime',0)>last and s.get('phoneState','').startswith('运行中') and s.get('mode')=='run':
            last=s['phoneTime']
            confirmations+=1
        if confirmations>=2:
            print('手机助手已激活并回报。可在“系统级按应用控制”管理或停用。拔线后请核对最近回报时间。')
            return
    raise ValueError('未收到手机助手的运行回报。可检查设备策略，并在恢复后重新激活。')

if __name__=='__main__':
    try:main()
    except (OSError,ValueError,IndexError,subprocess.SubprocessError) as problem:
        print(str(problem),file=sys.stderr);sys.exit(1)

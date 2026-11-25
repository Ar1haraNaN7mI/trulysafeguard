"""Build and sign the application with a local Android SDK, JDK 17+ and Python 3."""
import argparse
import os
from pathlib import Path
import re
import secrets
import shutil
import subprocess
import tempfile
from zipfile import ZipFile, ZIP_DEFLATED

ROOT=Path(__file__).resolve().parent
WIN=os.name=='nt'
def version(path):
    return tuple(int(n) for n in re.findall(r'\d+',path.name))

def main():
    args=argparse.ArgumentParser(description=__doc__)
    args.add_argument('--out',type=Path,default=ROOT/'build/PrivacyGuard.apk')
    args.add_argument('--sdk',type=Path)
    opts=args.parse_args()
    env=dict(os.environ)
    home=Path.home()
    sdk=opts.sdk or Path(env.get('ANDROID_SDK_ROOT') or env.get('ANDROID_HOME') or
                          (Path(env.get('LOCALAPPDATA',home))/'Android/Sdk' if WIN else home/'Android/Sdk'))
    platforms=sorted((p for p in (sdk/'platforms').glob('android-*') if (p/'android.jar').is_file() and version(p)>=(35,)),key=version)
    builds=sorted((p for p in (sdk/'build-tools').glob('*') if (p/('aapt2.exe' if WIN else 'aapt2')).is_file()),key=version)
    if not platforms or not builds:raise SystemExit('Install Android SDK platform 35+ and recent Build Tools; set ANDROID_SDK_ROOT.')
    jar=platforms[-1]/'android.jar'
    tools=builds[-1]
    jdk=env.get('JAVA_HOME')
    if not jdk and WIN:
        candidates=sorted(p for p in (Path(env.get('ProgramFiles','C:/Program Files'))/'Java').glob('jdk-*') if (p/'bin/keytool.exe').is_file())
        if candidates:jdk=str(candidates[-1])
    if jdk:
        env['JAVA_HOME']=jdk
        env['PATH']=str(Path(jdk)/'bin')+os.pathsep+env.get('PATH','')
    def java(name):
        path=shutil.which(name,path=env.get('PATH'))
        if not path:raise SystemExit('Set JAVA_HOME to a full JDK.')
        return path
    def tool(name):
        return tools/(name+('.bat' if WIN and name in ('d8','apksigner') else '.exe' if WIN else ''))
    def run(*cmd):subprocess.run([str(x) for x in cmd],check=True,env=env)
    build=ROOT/'build';build.mkdir(exist_ok=True)
    signing=ROOT/'.signing';signing.mkdir(exist_ok=True)
    key=signing/'guard.p12';secret=signing/'key-password'
    if not key.exists():
        password=secrets.token_urlsafe(32)
        env['GUARD_KEY_PASS']=password
        run(java('keytool'),'-genkeypair','-alias','privacyguard','-keystore',key,'-storetype','PKCS12','-storepass:env','GUARD_KEY_PASS',
            '-keypass:env','GUARD_KEY_PASS','-keyalg','RSA','-keysize','3072','-validity','10000','-dname','CN=Local Privacy Guard')
        secret.write_text(password,encoding='utf8');secret.chmod(0o600);key.chmod(0o600)
    env['GUARD_KEY_PASS']=secret.read_text(encoding='utf8').strip()
    opts.out=opts.out.resolve();opts.out.parent.mkdir(parents=True,exist_ok=True)
    with tempfile.TemporaryDirectory(dir=build) as tmp:
        w=Path(tmp);gen=w/'gen';classes=w/'classes';dex=w/'dex'
        for p in [gen,classes,dex]:p.mkdir()
        main=ROOT/'app/src/main'
        run(tool('aapt2'),'compile','--dir',main/'res','-o',w/'res.zip')
        run(tool('aapt2'),'link','-I',jar,'--manifest',main/'AndroidManifest.xml','-A',main/'assets','--java',gen,'-o',w/'unsigned.apk',w/'res.zip')
        files=sorted((main/'java').rglob('*.java'))+sorted(gen.rglob('*.java'))
        files+=sorted((ROOT/'phone-helper').glob('*.java'))
        run(java('javac'),'-encoding','UTF-8','--release','8','-classpath',jar,'-d',classes,*files)
        with ZipFile(w/'classes.jar','w',ZIP_DEFLATED) as z:
            for p in sorted(classes.rglob('*.class')):z.write(p,p.relative_to(classes).as_posix())
        run(tool('d8'),'--release','--min-api','26','--lib',jar,'--output',dex,w/'classes.jar')
        with ZipFile(w/'unsigned.apk','a',ZIP_DEFLATED) as z:
            for p in sorted(dex.glob('*.dex')):z.write(p,p.name)
        run(tool('zipalign'),'-f','4',w/'unsigned.apk',w/'aligned.apk')
        run(tool('apksigner'),'sign','--ks',key,'--ks-key-alias','privacyguard','--ks-pass','env:GUARD_KEY_PASS','--key-pass','env:GUARD_KEY_PASS',
            '--v4-signing-enabled','false','--out',w/'signed.apk',w/'aligned.apk')
        run(tool('apksigner'),'verify','--verbose',w/'signed.apk')
        shutil.copyfile(w/'signed.apk',opts.out)
    print('APK:',opts.out,flush=True)
if __name__=='__main__':main()

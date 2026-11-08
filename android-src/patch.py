"""Turns the freshly generated Capacitor Android project into the Sauti app:
native media service + plugin, permissions, icons, signing and version number."""
import os, re, shutil, sys

APP = sys.argv[1]                                   # folder that contains android/
SRC = os.path.dirname(os.path.abspath(__file__))
A = os.path.join(APP, 'android', 'app')
MAIN = os.path.join(A, 'src', 'main')

# 1. Java sources
jdir = os.path.join(MAIN, 'java', 'io', 'sauti', 'app')
os.makedirs(jdir, exist_ok=True)
for f in os.listdir(os.path.join(SRC, 'java')):
    shutil.copy(os.path.join(SRC, 'java', f), os.path.join(jdir, f))

# 2. Resources (icons, notification icon, colours)
shutil.copytree(os.path.join(SRC, 'res'), os.path.join(MAIN, 'res'), dirs_exist_ok=True)

# 3. Manifest: permissions + media service + media-button receiver
mf = os.path.join(MAIN, 'AndroidManifest.xml')
m = open(mf).read()
perms = ['android.permission.FOREGROUND_SERVICE', 'android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK',
         'android.permission.WAKE_LOCK', 'android.permission.POST_NOTIFICATIONS', 'android.permission.ACCESS_WIFI_STATE',
         'android.permission.INTERNET']
add = ''.join(f'    <uses-permission android:name="{p}" />\n' for p in perms if p not in m)
m = m.replace('<application', add + '\n    <application', 1)
svc = '''
        <service android:name=".PlaybackService" android:exported="true" android:foregroundServiceType="mediaPlayback">
            <intent-filter><action android:name="android.intent.action.MEDIA_BUTTON" /></intent-filter>
        </service>
        <receiver android:name="androidx.media.session.MediaButtonReceiver" android:exported="true">
            <intent-filter><action android:name="android.intent.action.MEDIA_BUTTON" /></intent-filter>
        </receiver>
'''
m = m.replace('</application>', svc + '    </application>', 1)
open(mf, 'w').write(m)

# 4. Gradle: media library, signing, version
gf = os.path.join(A, 'build.gradle')
g = open(gf).read()
g = re.sub(r'dependencies\s*\{', 'dependencies {\n    implementation "androidx.media:media:1.7.0"', g, count=1)
run = int(os.environ.get('GITHUB_RUN_NUMBER', '1'))
g = re.sub(r'versionCode\s+\d+', f'versionCode {run}', g, count=1)
g = re.sub(r'versionName\s+"[^"]*"', f'versionName "1.{run}"', g, count=1)
shutil.copy(os.path.join(SRC, 'sauti-release.p12'), os.path.join(A, 'sauti-release.p12'))
signing = '''
    signingConfigs {
        release {
            storeFile file("sauti-release.p12")
            storeType "pkcs12"
            storePassword "sauti-app-key"
            keyAlias "sauti"
            keyPassword "sauti-app-key"
        }
    }
'''
g = re.sub(r'(android\s*\{)', r'\1' + signing.replace('\\', '\\\\'), g, count=1)
g = re.sub(r'(buildTypes\s*\{\s*release\s*\{)', r'\1\n            signingConfig signingConfigs.release', g, count=1)
open(gf, 'w').write(g)

# 5. App name
sf = os.path.join(MAIN, 'res', 'values', 'strings.xml')
if os.path.exists(sf):
    s = open(sf).read()
    s = re.sub(r'(<string name="app_name">)[^<]*(</string>)', r'\1Sauti\2', s)
    s = re.sub(r'(<string name="title_activity_main">)[^<]*(</string>)', r'\1Sauti\2', s)
    open(sf, 'w').write(s)
print('Sauti Android project patched (version 1.%d)' % run)

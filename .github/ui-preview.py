"""Offline visual smoke only. No media requests or playback tested."""
import glob
import pathlib
import re
import subprocess
import time
import xml.etree.ElementTree as ET

OUT=pathlib.Path("ui-preview")
OUT.mkdir(exist_ok=True)
PKG="com.hongguo.watch"

def adb(*args, check=True):
    return subprocess.run(["adb",*args],check=check,stdout=subprocess.PIPE,stderr=subprocess.STDOUT).stdout

def shell(*args,check=True):
    return adb("shell",*args,check=check)

def screenshot(name):
    time.sleep(1)
    (OUT/(name+".png")).write_bytes(adb("exec-out","screencap","-p"))
    print("CAPTURED",name,flush=True)

def dump():
    shell("uiautomator","dump","/sdcard/ui.xml")
    return ET.fromstring(adb("exec-out","cat","/sdcard/ui.xml"))

def tap_id(name):
    for i in range(4):
        try:
            node=next(n for n in dump().iter("node") if n.get("resource-id")==PKG+":id/"+name)
            x1,y1,x2,y2=map(int,re.findall(r"\d+",node.attrib["bounds"]))
            shell("input","tap",str((x1+x2)//2),str((y1+y2)//2))
            time.sleep(2)
            return True
        except Exception:
            time.sleep(2)
    print("NOT FOUND",name,flush=True)
    return False

apk=glob.glob("app/build/outputs/apk/debug/*.apk")[0]
print(adb("install","-r",apk).decode(errors="replace"),flush=True)
shell("wm","size","408x502")
shell("wm","density","240")
shell("settings","put","system","font_scale","1.0")
shell("settings","put","system","screen_off_timeout","600000")
shell("settings","put","global","airplane_mode_on","1")
shell("am","broadcast","-a","android.intent.action.AIRPLANE_MODE","--ez","state","true",check=False)
shell("svc","wifi","disable",check=False)
shell("svc","data","disable",check=False)
shell("input","keyevent","82",check=False)
shell("am","start","-W","-n",PKG+"/.ui.MainActivity")
time.sleep(10)
screenshot("01-home-offline")
if tap_id("navSearch"):
    screenshot("02-search-offline")
if tap_id("tabLibrary"):
    screenshot("03-library-favorites")
    if tap_id("segmentHistory"):
        screenshot("04-library-history")
    if tap_id("segmentOffline"):
        screenshot("05-library-offline")
if tap_id("tabSettings"):
    screenshot("06-settings-top")
    shell("input","swipe","204","420","204","125","400")
    screenshot("07-settings-scrolled")
    shell("input","swipe","204","420","204","125","400")
    screenshot("08-settings-bottom")
(OUT/"logcat.txt").write_bytes(adb("logcat","-d","-t","400"))
print("Offline visual smoke complete",flush=True)

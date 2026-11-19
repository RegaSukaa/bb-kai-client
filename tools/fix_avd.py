from pathlib import Path

p = Path.home() / ".android" / "avd" / "bbkai.avd" / "config.ini"
s = p.read_text(encoding="utf-8")
old = "image.sysdir.1=android-sdk" + "\\" + "system-images" + "\\" + "android-34" + "\\" + "google_apis" + "\\" + "x86_64" + "\\"
new = "image.sysdir.1=system-images" + "\\" + "android-34" + "\\" + "google_apis" + "\\" + "x86_64" + "\\"
assert old in s, "image.sysdir.1 не найден"
s = s.replace(old, new)
s = s.replace("hw.gpu.enabled=no", "hw.gpu.enabled=yes")
s = s.replace("hw.gpu.mode=auto", "hw.gpu.mode=swiftshader_indirect")
s = s.replace("hw.ramSize=1536", "hw.ramSize=3072")
p.write_text(s, encoding="utf-8")
print([l for l in s.splitlines() if "sysdir" in l or "gpu" in l or "ramSize" in l])

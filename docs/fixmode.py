import pytsk3, sys, os, stat
img=pytsk3.Img_Info(sys.argv[1]); fs=pytsk3.FS_Info(img)
root=sys.argv[2]; strip=sys.argv[3] if len(sys.argv)>3 else ""
n=0
def walk(d, base):
    global n
    for e in d:
        nm=e.info.name.name.decode("utf-8","replace")
        if nm in (".",".."): continue
        m=e.info.meta
        if m is None: continue
        p=base+"/"+nm; host=root+p
        try: os.chmod(host, m.mode & 0o7777)
        except Exception: pass
        n+=1
        if m.type==pytsk3.TSK_FS_META_TYPE_DIR:
            try: walk(e.as_directory(), p)
            except Exception: pass
walk(fs.open_dir(path="/"), strip)
print("chmod entries",n)

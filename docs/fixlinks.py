import pytsk3, sys, os
img = pytsk3.Img_Info(sys.argv[1]); fs = pytsk3.FS_Info(img)
root = sys.argv[2]; strip = sys.argv[3] if len(sys.argv)>3 else ""
ok=bad=0
def walk(d, base):
    global ok,bad
    for e in d:
        nm = e.info.name.name.decode("utf-8","replace")
        if nm in (".",".."): continue
        m = e.info.meta
        if m is None: continue
        p = base + "/" + nm; host = root + p
        if m.type == pytsk3.TSK_FS_META_TYPE_DIR:
            try: walk(e.as_directory(), p)
            except Exception: pass
        elif m.type == pytsk3.TSK_FS_META_TYPE_LNK:
            try:
                raw = e.read_random(0, m.size)
                tgt = raw.split(b"\x00")[0].decode("utf-8","replace")
                if not tgt: bad+=1; continue
                if os.path.lexists(host):
                    if os.path.islink(host): os.unlink(host)
                    else: continue
                os.symlink(tgt, host); ok+=1
            except Exception as ex:
                bad+=1
walk(fs.open_dir(path="/"), strip)
print("links fixed",ok,"bad",bad)

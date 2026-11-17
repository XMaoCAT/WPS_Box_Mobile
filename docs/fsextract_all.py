import pytsk3, sys, os, time
img = pytsk3.Img_Info(sys.argv[1]); fs = pytsk3.FS_Info(img)
root = sys.argv[2]; strip = sys.argv[3] if len(sys.argv)>3 else ""
os.makedirs(root, exist_ok=True)
dircache = {}
def mkdirs(path):
    if path in dircache: return dircache[path]
    os.makedirs(path, exist_ok=True); dircache[path]=path; return path
stat=[0,0,0]
def walk(d, base):
    for e in d:
        nm = e.info.name.name.decode("utf-8","replace")
        if nm in (".",".."): continue
        m = e.info.meta
        if m is None: continue
        p = base + "/" + nm
        host = root + p
        if m.type == pytsk3.TSK_FS_META_TYPE_DIR:
            mkdirs(host)
            try: walk(e.as_directory(), p)
            except Exception as ex: print("ERRDIR",p,ex, flush=True)
        elif m.type == pytsk3.TSK_FS_META_TYPE_LNK:
            try:
                tgt = e.read_random(0, m.size).decode("utf-8","replace")
            except Exception: tgt = ""
            try:
                os.makedirs(os.path.dirname(host), exist_ok=True)
                if os.path.lexists(host): os.unlink(host)
                os.symlink(tgt, host)
            except Exception as ex: print("ERRLNK",p,ex, flush=True)
            stat[2]+=1
        else:
            try:
                os.makedirs(os.path.dirname(host), exist_ok=True)
                sz = m.size
                with open(host,"wb") as o:
                    off=0
                    while off < sz:
                        try: dd = e.read_random(off, min(1<<20, sz-off))
                        except Exception: break
                        if not dd: break
                        o.write(dd); off += len(dd)
                stat[0]+=1; stat[1]+=sz
                if stat[0] % 5000 == 0: print("progress", stat[0], stat[1]//1048576, "MiB", flush=True)
            except Exception as ex: print("ERRFILE",p,ex, flush=True)
walk(fs.open_dir(path="/"), strip)
print("DONE files",stat[0],"bytes",stat[1],"symlinks",stat[2], flush=True)

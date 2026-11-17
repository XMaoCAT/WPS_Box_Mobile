import pytsk3, sys, stat, datetime

img = pytsk3.Img_Info(sys.argv[1])
fs = pytsk3.FS_Info(img)
out = open(sys.argv[2], "w", buffering=1<<20)
n=[0]; tot=[0]
def walk(d, path):
    try:
        for e in d:
            nm = e.info.name.name.decode("utf-8","replace")
            if nm in (".",".."): continue
            m = e.info.meta
            if m is None: continue
            p = path + "/" + nm
            t = m.type
            if t == pytsk3.TSK_FS_META_TYPE_DIR:
                out.write(f"D {m.size:>12} {p}\n")
                n[0]+=1
                try: walk(e.as_directory(), p)
                except Exception as ex: out.write(f"! ERR {p} {ex}\n")
            else:
                out.write(f"F {m.size:>12} {p}\n")
                tot[0]+=m.size; n[0]+=1
    except Exception as ex:
        out.write(f"! ERRWALK {path} {ex}\n")
walk(fs.open_dir(path="/"), "")
out.close()
print("entries",n[0],"bytes",tot[0])

import pytsk3, sys, os
img = pytsk3.Img_Info(sys.argv[1]); fs = pytsk3.FS_Info(img)
f = fs.open(path=sys.argv[2])
size = f.info.meta.size
with open(sys.argv[3],"wb") as o:
    off=0
    while off < size:
        try: d=f.read_random(off, min(1<<20, size-off))
        except Exception: break
        if not d: break
        o.write(d); off+=len(d)
print("size", os.path.getsize(sys.argv[3]), "of", size)

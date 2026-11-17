import pytsk3, sys
img = pytsk3.Img_Info(sys.argv[1]); fs = pytsk3.FS_Info(img)
d = fs.open_dir(path=sys.argv[2] if len(sys.argv)>2 else "/")
for e in d:
    if e.info.name.name in (b".",b".."): continue
    print(e.info.name.name.decode("utf-8","replace"), e.info.meta.size if e.info.meta else "?", e.info.meta.type if e.info.meta else "")

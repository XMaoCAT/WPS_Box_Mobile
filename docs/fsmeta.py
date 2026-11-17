import pytsk3, sys
img = pytsk3.Img_Info(sys.argv[1]); fs = pytsk3.FS_Info(img)
for e in fs.open_dir(path=sys.argv[2]):
    n=e.info.name.name.decode("utf-8","replace")
    if n in (".",".."): continue
    m=e.info.meta
    ty={1:"F",2:"D",5:"SYM",3:"?"}.get(m.type if m else 0, str(m.type if m else None))
    extra=""
    if m and m.type==pytsk3.TSK_FS_META_TYPE_LNK:
        try: extra=" -> "+e.read_random(0,m.size).decode("utf-8","replace")
        except Exception as ex: extra=" ?"
    print(f"{ty} {m.size if m else 0:>10} {n}{extra}")

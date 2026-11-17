import struct, sys, os

class Ext4:
    def __init__(self, path):
        self.f = open(path, "rb")
        self.f.seek(1024); sb = self.f.read(1024)
        (self.inodes, self.blocks, self.rblocks, self.freeb, self.freei,
         self.first_data, self.log_bs, self.log_cs, self.bpg, self.cpg,
         self.ipg) = struct.unpack_from("<11I", sb, 0)
        self.bs = 1024 << self.log_bs
        self.inode_size = struct.unpack_from("<H", sb, 88)[0] or 128
        self.incompat = struct.unpack_from("<I", sb, 96)[0]
        desc_size = struct.unpack_from("<H", sb, 254)[0] if self.incompat & 0x80 else 0
        self.desc_size = desc_size if desc_size >= 32 else 32
        gdt_blk = 2 if self.bs == 1024 else 1
        self.gdt_off = gdt_blk * self.bs
    def inode_table(self, group):
        self.f.seek(self.gdt_off + group * self.desc_size)
        d = self.f.read(self.desc_size)
        return struct.unpack_from("<I", d, 8)[0]
    def read_inode(self, ino):
        g = (ino - 1) // self.ipg
        i = (ino - 1) % self.ipg
        off = self.inode_table(g) * self.bs + i * self.inode_size
        self.f.seek(off); return self.f.read(self.inode_size)
    def fast_symlink(self, ino):
        d = self.read_inode(ino)
        size = struct.unpack_from("<I", d, 4)[0]
        if size >= 60: return None
        return d[40:40+size].split(b"\x00")[0].decode("utf-8", "replace")

img = Ext4(sys.argv[1]); root = sys.argv[2]; strip = sys.argv[3] if len(sys.argv) > 3 else ""
sys.path.insert(0, ".")
import pytsk3
fs = pytsk3.FS_Info(pytsk3.Img_Info(sys.argv[1]))
ok = bad = 0
def walk(d, base):
    global ok, bad
    for e in d:
        nm = e.info.name.name.decode("utf-8", "replace")
        if nm in (".", ".."): continue
        m = e.info.meta
        if m is None: continue
        p = base + "/" + nm; host = root + p
        if m.type == pytsk3.TSK_FS_META_TYPE_DIR:
            try: walk(e.as_directory(), p)
            except Exception: pass
        elif m.type == pytsk3.TSK_FS_META_TYPE_LNK:
            tgt = None
            try:
                raw = e.read_random(0, max(m.size, 1))
                t = raw.split(b"\x00")[0].decode("utf-8", "replace")
                if t: tgt = t
            except Exception: pass
            if not tgt:
                tgt = img.fast_symlink(m.addr)
            if not tgt:
                bad += 1; print("BADLINK", p, "size", m.size, flush=True); continue
            try:
                os.makedirs(os.path.dirname(host), exist_ok=True)
                if os.path.lexists(host):
                    if os.path.islink(host): os.unlink(host)
                    else: continue
                os.symlink(tgt, host); ok += 1
            except Exception as ex:
                bad += 1; print("ERRLNK", p, ex, flush=True)
walk(fs.open_dir(path="/"), strip)
print("links fixed", ok, "bad", bad)

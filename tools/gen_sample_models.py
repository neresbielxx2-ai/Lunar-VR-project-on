#!/usr/bin/env python3
"""Generates the AGUS VR sample 3D models (GLB cube, GLB sphere, OBJ pyramid).

GLB layout: 12-byte header + JSON chunk (space padded) + BIN chunk (zero padded).
Output is written next to this script's parent assets/samples directory.
"""
import json, math, struct, os, sys

OUT = os.path.join(os.path.dirname(__file__), '..', 'app', 'src', 'main', 'assets', 'samples')
OUT = os.path.abspath(OUT)
os.makedirs(OUT, exist_ok=True)

def write_glb(path, positions, normals, indices, name, base_color):
    pos = b''.join(struct.pack('<3f', *p) for p in positions)
    nrm = b''.join(struct.pack('<3f', *n) for n in normals)
    idx = b''.join(struct.pack('<H', i) for i in indices)
    # align each bufferView to 4 bytes
    def pad(b):
        r = len(b) % 4
        return b + (b'\x00' * (4 - r) if r else b'')
    pos_off = 0; pos_len = len(pos)
    nrm_off = len(pad(pos)); nrm_len = len(nrm)
    idx_off = nrm_off + len(pad(nrm)); idx_len = len(idx)
    bin_data = pad(pos) + pad(nrm) + pad(idx)
    mins = [min(p[i] for p in positions) for i in range(3)]
    maxs = [max(p[i] for p in positions) for i in range(3)]
    gltf = {
        "asset": {"version": "2.0", "generator": "Agus VR Sample Generator"},
        "scene": 0,
        "scenes": [{"nodes": [0], "name": name}],
        "nodes": [{"mesh": 0, "name": name}],
        "meshes": [{"name": name, "primitives": [{
            "attributes": {"POSITION": 0, "NORMAL": 1},
            "indices": 2, "material": 0}]}],
        "materials": [{"name": name + "Mat", "pbrMetallicRoughness": {
            "baseColorFactor": base_color, "metallicFactor": 0.25, "roughnessFactor": 0.35},
            "emissiveFactor": [base_color[0]*0.08, base_color[1]*0.08, base_color[2]*0.08]}],
        "accessors": [
            {"bufferView": 0, "componentType": 5126, "count": len(positions),
             "type": "VEC3", "min": mins, "max": maxs},
            {"bufferView": 1, "componentType": 5126, "count": len(normals), "type": "VEC3"},
            {"bufferView": 2, "componentType": 5123, "count": len(indices), "type": "SCALAR"}],
        "bufferViews": [
            {"buffer": 0, "byteOffset": pos_off, "byteLength": pos_len, "target": 34962},
            {"buffer": 0, "byteOffset": nrm_off, "byteLength": nrm_len, "target": 34962},
            {"buffer": 0, "byteOffset": idx_off, "byteLength": idx_len, "target": 34963}],
        "buffers": [{"byteLength": len(bin_data)}],
    }
    js = json.dumps(gltf, separators=(',', ':')).encode('utf-8')
    js = js + b' ' * ((4 - len(js) % 4) % 4)
    total = 12 + 8 + len(js) + 8 + len(bin_data)
    with open(path, 'wb') as f:
        f.write(struct.pack('<III', 0x46546C67, 2, total))
        f.write(struct.pack('<II', len(js), 0x4E4F534A) + js)
        f.write(struct.pack('<II', len(bin_data), 0x004E4942) + bin_data)
    print('wrote', path, os.path.getsize(path), 'bytes')

# ---------------- Cube (per-face vertices for flat normals) ----------------
faces = [
    (+1, [(1,-1,-1),(1,1,-1),(1,1,1),(1,-1,1)], (1,0,0)),
    (-1, [(-1,-1,1),(-1,1,1),(-1,1,-1),(-1,-1,-1)], (-1,0,0)),
    (+2, [(-1,1,-1),(-1,1,1),(1,1,1),(1,1,-1)], (0,1,0)),
    (-2, [(-1,-1,1),(-1,-1,-1),(1,-1,-1),(1,-1,1)], (0,-1,0)),
    (+3, [(-1,-1,1),(1,-1,1),(1,1,1),(-1,1,1)], (0,0,1)),
    (-3, [(1,-1,-1),(-1,-1,-1),(-1,1,-1),(1,1,-1)], (0,0,-1)),
]
pos, nrm, idx = [], [], []
for _, quad, n in faces:
    base = len(pos)
    for v in quad:
        pos.append((v[0]*0.5, v[1]*0.5, v[2]*0.5)); nrm.append(n)
    idx += [base, base+1, base+2, base, base+2, base+3]
write_glb(os.path.join(OUT, 'agus_cube.glb'), pos, nrm, idx, 'AgusCube', [0.30, 0.91, 1.0, 1.0])

# ---------------- UV Sphere ----------------
SEG, RING = 24, 16
pos, nrm, idx = [], [], []
for r in range(RING + 1):
    phi = math.pi * r / RING
    for s in range(SEG + 1):
        th = 2 * math.pi * s / SEG
        x, y, z = math.sin(phi) * math.cos(th), math.cos(phi), math.sin(phi) * math.sin(th)
        pos.append((x * 0.55, y * 0.55, z * 0.55)); nrm.append((x, y, z))
for r in range(RING):
    for s in range(SEG):
        a = r * (SEG + 1) + s; b = a + SEG + 1
        idx += [a, b, a + 1, a + 1, b, b + 1]
write_glb(os.path.join(OUT, 'agus_sphere.glb'), pos, nrm, idx, 'AgusSphere', [0.62, 0.42, 1.0, 1.0])

# ---------------- OBJ pyramid (text) ----------------
obj = """# Agus VR sample — low-poly pyramid (OBJ, triangles + flat normals)
# Importe no AGUS MODEL LAB (.obj)
o AgusPyramid
v -0.7 -0.5 0.7
v 0.7 -0.5 0.7
v 0.7 -0.5 -0.7
v -0.7 -0.5 -0.7
v 0.0 0.9 0.0
vn 0.0 -1.0 0.0
vn 0.0 0.661 0.750
vn 0.750 0.661 0.0
vn 0.0 0.661 -0.750
vn -0.750 0.661 0.0
f 1//1 3//1 2//1
f 1//1 4//1 3//1
f 1//2 2//2 5//2
f 2//3 3//3 5//3
f 3//4 4//4 5//4
f 4//5 1//5 5//5
"""
p = os.path.join(OUT, 'agus_pyramid.obj')
open(p, 'w').write(obj)
print('wrote', p, os.path.getsize(p), 'bytes')

# ---------------- validate GLBs parse back ----------------
def validate(path):
    d = open(path, 'rb').read()
    magic, ver, length = struct.unpack('<III', d[:12])
    assert magic == 0x46546C67 and ver == 2 and length == len(d), path
    clen, ctype = struct.unpack('<II', d[12:20])
    assert ctype == 0x4E4F534A
    j = json.loads(d[20:20+clen])
    boff = 20 + clen
    blen, btype = struct.unpack('<II', d[boff:boff+8])
    assert btype == 0x004E4942 and blen == j['buffers'][0]['byteLength']
    print('valid GLB:', path, '— mesh:', j['meshes'][0]['name'])
validate(os.path.join(OUT, 'agus_cube.glb'))
validate(os.path.join(OUT, 'agus_sphere.glb'))
print('OK')

"""Tiny software renderer for checking the exported GLB: reads nodes/meshes/materials/animation, z-buffer, lambert + rim."""
import json, struct, sys, io, math
import numpy as np
from PIL import Image

def load(path):
    d = open(path, 'rb').read()
    jl = struct.unpack_from('<I', d, 12)[0]; g = json.loads(d[20:20 + jl]); bo = 20 + jl + 8
    def acc(i):
        a = g['accessors'][i]; v = g['bufferViews'][a['bufferView']]
        n = {'SCALAR': 1, 'VEC2': 2, 'VEC3': 3, 'VEC4': 4}[a['type']]; dt = {5126: np.float32, 5123: np.uint16, 5125: np.uint32}[a['componentType']]
        return np.frombuffer(d, dt, a['count'] * n, bo + v['byteOffset']).reshape(a['count'], n) if n > 1 else np.frombuffer(d, dt, a['count'], bo + v['byteOffset'])
    img = None
    if g.get('images'):
        v = g['bufferViews'][g['images'][0]['bufferView']]; img = np.array(Image.open(io.BytesIO(d[bo + v['byteOffset']: bo + v['byteOffset'] + v['byteLength']])).convert('RGBA'))
    return g, acc, img

def qmat(q):
    x, y, z, w = q
    return np.array([[1-2*(y*y+z*z), 2*(x*y-z*w), 2*(x*z+y*w)], [2*(x*y+z*w), 1-2*(x*x+z*z), 2*(y*z-x*w)], [2*(x*z-y*w), 2*(y*z+x*w), 1-2*(x*x+y*y)]])

def collect(path, base=None, t=0.0, show=(), hide=(), clip=0):
    g, acc, tex = load(path)
    over = {}
    for an in g.get('animations', [])[clip:clip + 1]:
        for ch in an['channels']:
            s = an['samplers'][ch['sampler']]; ti = acc(s['input']); vo = acc(s['output'])
            k = int(np.clip(np.searchsorted(ti, t % ti[-1]), 0, len(ti) - 1)); over[(ch['target']['node'], ch['target']['path'])] = vo[k]
    tris = []
    def walk(i, M):
        n = g['nodes'][i]
        T = np.eye(4); T[:3, 3] = over.get((i, 'translation'), n.get('translation', [0, 0, 0]))
        R = np.eye(4); R[:3, :3] = qmat(over.get((i, 'rotation'), n.get('rotation', [0, 0, 0, 1])))
        sc = [1, 1, 1] if n.get('name') in show else [1e-4] * 3 if n.get('name') in hide else n.get('scale', [1, 1, 1])
        S = np.diag(list(sc) + [1])
        W = M @ T @ R @ S
        if 'mesh' in n:
            for p in g['meshes'][n['mesh']]['primitives']:
                P = acc(p['attributes']['POSITION']); N = acc(p['attributes']['NORMAL']); I = acc(p['indices']).reshape(-1, 3)
                UV = acc(p['attributes']['TEXCOORD_0']) if 'TEXCOORD_0' in p['attributes'] else None
                m = g['materials'][p['material']]
                Pw = (W @ np.c_[P, np.ones(len(P))].T).T[:, :3]; Nw = (np.linalg.inv(W[:3, :3]).T @ N.T).T; Nw /= np.linalg.norm(Nw, axis=1, keepdims=True) + 1e-9
                tris.append((Pw, Nw, I, UV, m, tex))
        for c in n.get('children', []): walk(c, W)
    for r in g['scenes'][0]['nodes']: walk(r, np.eye(4) if base is None else base)
    return tris

def render(path, out, t=0.0, yaw=0.5, size=420, show=(), hide=(), clip=0, pitch=0.0, zoom=0.62, cy=0.08, items=None, bg=(0.86, 0.9, 0.95), cam=None, fov=1.3):
    tris = collect(path, None, t, show, hide, clip) if items is None else [x for it in items for x in collect(*it)]
    img = np.zeros((size, size, 3)) + np.array(bg); zb = np.full((size, size), 1e9)
    cy_ = cy; cy, sy = math.cos(yaw), math.sin(yaw); L = np.array([-0.4, 0.7, 0.6]); L /= np.linalg.norm(L)
    for Pw, Nw, I, UV, m, tex in tris:
        Q = Pw - np.array(cam) if cam is not None else Pw
        x = Q[:, 0] * cy + Q[:, 2] * sy; z0 = -Q[:, 0] * sy + Q[:, 2] * cy; y0 = Q[:, 1]
        cp, sp = math.cos(pitch), math.sin(pitch); y = y0 * cp + z0 * sp; z = -y0 * sp + z0 * cp
        nx = Nw[:, 0] * cy + Nw[:, 2] * sy; nz0 = -Nw[:, 0] * sy + Nw[:, 2] * cy; ny = Nw[:, 1] * math.cos(pitch) + nz0 * math.sin(pitch); nz = -Nw[:, 1] * math.sin(pitch) + nz0 * math.cos(pitch); Nv = np.c_[nx, ny, nz]
        if cam is not None:
            d = np.maximum(-z, 0.05); sx = (x / d * fov * 0.5 + 0.5) * size; syy = (0.5 - y / d * fov * 0.5) * size; z = -d
        else:
            sx = (x * zoom + 0.5) * size; syy = (1.0 - (y * zoom + cy_)) * size
        col = np.array(m['pbrMetallicRoughness']['baseColorFactor'][:3]); unlit = 'extensions' in m
        cut = m.get('alphaCutoff', 0) if m.get('alphaMode') == 'MASK' else None
        for a, b, c in I:
            xs = np.array([sx[a], sx[b], sx[c]]); ys = np.array([syy[a], syy[b], syy[c]])
            x0, x1 = int(max(0, xs.min())), int(min(size - 1, xs.max() + 1)); y0, y1 = int(max(0, ys.min())), int(min(size - 1, ys.max() + 1))
            if x1 < x0 or y1 < y0: continue
            den = (ys[1] - ys[2]) * (xs[0] - xs[2]) + (xs[2] - xs[1]) * (ys[0] - ys[2])
            if abs(den) < 1e-9: continue
            X, Y = np.meshgrid(np.arange(x0, x1 + 1) + 0.5, np.arange(y0, y1 + 1) + 0.5)
            w0 = ((ys[1] - ys[2]) * (X - xs[2]) + (xs[2] - xs[1]) * (Y - ys[2])) / den; w1 = ((ys[2] - ys[0]) * (X - xs[2]) + (xs[0] - xs[2]) * (Y - ys[2])) / den; w2 = 1 - w0 - w1
            ins = (w0 >= 0) & (w1 >= 0) & (w2 >= 0)
            if not ins.any(): continue
            zz = -(w0 * z[a] + w1 * z[b] + w2 * z[c])
            if cut is not None and UV is not None:
                u = (w0 * UV[a, 0] + w1 * UV[b, 0] + w2 * UV[c, 0]) % 1; v = (w0 * UV[a, 1] + w1 * UV[b, 1] + w2 * UV[c, 1]) % 1
                al = tex[(v * tex.shape[0]).astype(int) % tex.shape[0], (u * tex.shape[1]).astype(int) % tex.shape[1], 3] / 255.0
                ins &= al >= cut
            sub = zb[y0:y1 + 1, x0:x1 + 1]; win = ins & (zz < sub)
            if not win.any(): continue
            n = Nv[a] + Nv[b] + Nv[c]; n /= np.linalg.norm(n) + 1e-9
            sh = 1.0 if unlit else 0.35 + 0.75 * max(0, n @ L) + 0.25 * (1 - abs(n[2])) ** 3
            cc = np.clip(col * sh, 0, 1) ** (1 / 2.2)
            al = m['pbrMetallicRoughness']['baseColorFactor'][3] if m.get('alphaMode') == 'BLEND' else 1.0
            if m.get('alphaMode') == 'BLEND' and UV is not None and tex is not None and 'baseColorTexture' in m['pbrMetallicRoughness']:
                u = (w0 * UV[a, 0] + w1 * UV[b, 0] + w2 * UV[c, 0]) % 1; v = (w0 * UV[a, 1] + w1 * UV[b, 1] + w2 * UV[c, 1]) % 1
                ta = tex[(v * tex.shape[0]).astype(int) % tex.shape[0], (u * tex.shape[1]).astype(int) % tex.shape[1], 3] / 255.0
                blk = img[y0:y1 + 1, x0:x1 + 1]; blk[win] = blk[win] * (1 - ta[win, None]) + cc * ta[win, None]; continue
            if al < 1: blk = img[y0:y1 + 1, x0:x1 + 1]; blk[win] = blk[win] * (1 - al) + cc * al; continue
            sub[win] = zz[win]; img[y0:y1 + 1, x0:x1 + 1][win] = cc
    Image.fromarray((img * 255).astype(np.uint8)).save(out)

if __name__ == '__main__':
    a = sys.argv
    render(a[1], a[2], float(a[3]) if len(a) > 3 else 0.0, float(a[4]) if len(a) > 4 else 0.5,
           show=a[5].split(',') if len(a) > 5 else (), hide=a[6].split(',') if len(a) > 6 else ())

"""Pharaoh's Book maths: exact base game (every stop combination) + free spins by simulation. Prints the RTP."""
import numpy as np, itertools, random, sys
SYM = ['BOOK','MASK','SCARAB','EYE','ANKH','A','K','Q','J','TEN']
PAYS = {'MASK':(20,100,500),'SCARAB':(15,60,300),'EYE':(10,40,150),'ANKH':(10,40,150),'A':(5,25,100),'K':(5,25,100),'Q':(4,15,75),'J':(4,15,75),'TEN':(4,15,75)}
SCAT = {3:2,4:20,5:200}
LINES = [(1,1,1,1,1),(0,0,0,0,0),(2,2,2,2,2),(0,1,2,1,0),(2,1,0,1,2),(0,0,1,2,2),(2,2,1,0,0),(1,0,0,0,1),(1,2,2,2,1),(0,1,1,1,0)]
def strips(counts, seed):
    rnd = random.Random(seed); out = []
    for reel in counts:
        while True:
            s = [k for k, n in reel.items() for _ in range(n)]; rnd.shuffle(s)
            L = len(s); ok = all(not (s[i] == 'BOOK' and (s[(i+1)%L] == 'BOOK' or s[(i+2)%L] == 'BOOK')) for i in range(L))
            if ok: out.append(s); break
    return out
def counts(book=(2,2,2,2,2)):
    base = {'MASK':2,'SCARAB':3,'EYE':3,'ANKH':3,'A':4,'K':4,'Q':4,'J':5,'TEN':5}
    return [dict(base, BOOK=book[i]) for i in range(5)]
def line_pay(syms):
    w = 0
    while w < 5 and syms[w] == 'BOOK': w += 1
    t = next((s for s in syms if s != 'BOOK'), None)
    if t is None: return 0
    n = 0
    while n < 5 and (syms[n] == t or syms[n] == 'BOOK'): n += 1
    return PAYS[t][n-3] if n >= 3 else 0
def spin_value(grid, special=None):
    """grid[row][col]; returns (line+scatter win in line bets, books, expanding win in line bets)"""
    win = sum(line_pay([grid[r][c] for c, r in enumerate(l)]) for l in LINES)
    books = sum(1 for r in range(3) for c in range(5) if grid[r][c] == 'BOOK')
    win += SCAT.get(books, 0) * 10
    exp = 0
    if special:
        k = sum(1 for c in range(5) if any(grid[r][c] == special for r in range(3)))
        if k >= 3: exp = PAYS[special][k-3] * 10
    return win, books, exp
def run(st, n_base=400000, seed=1):
    rnd = random.Random(seed); tot = 0.0; trig = 0
    def spin():
        stops = [rnd.randrange(len(s)) for s in st]
        return [[st[c][(stops[c]+r) % len(st[c])] for c in range(5)] for r in range(3)]
    for _ in range(n_base):
        w, b, _ = spin_value(spin()); tot += w
        if b >= 3:
            trig += 1; left = 10; special = rnd.choice(SYM[1:])
            while left > 0:
                left -= 1; w2, b2, e2 = spin_value(spin(), special); tot += w2 + e2
                if b2 >= 3: left += 10
    return tot / (n_base * 10), trig / n_base
if __name__ == '__main__':
    st = strips(counts(), 7)
    rtp, f = run(st, int(sys.argv[1]) if len(sys.argv) > 1 else 200000)
    print('RTP %.4f  FS freq 1/%.0f  strip lens %s' % (rtp, 1/max(f,1e-9), [len(s) for s in st]))

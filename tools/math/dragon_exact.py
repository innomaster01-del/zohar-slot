"""Exact RTP of Dragon Fortune (243 ways + Hold & Win), from the reel strips.
   Ways: a symbol pays when it (or the dragon wild) shows on reels 1..n in a row; win = pay × (product of matches per reel)
   × total bet / 25. Hold & Win: 6+ coins → 3 respins, each empty cell lands a coin with probability P_RESPIN, any new coin
   resets the respins to 3; the coins pay their values (× total bet), all 15 cells = GRAND (1000 × total bet) on top."""
import random, itertools
SYM = ['WILD','PHOENIX','TIGER','KOI','TURTLE','A','K','Q','J','TEN','COIN']
PAYS = {'PHOENIX':(20,50,150),'TIGER':(15,40,100),'KOI':(10,30,80),'TURTLE':(10,25,60),'A':(5,10,40),'K':(5,10,30),'Q':(5,8,25),'J':(3,8,20),'TEN':(3,6,20)}
VALUES = [(1,300),(2,220),(3,150),(5,120),(8,80),(10,60),(15,40),(20,20),(50,8),(250,2)]   # × total bet; 20 = MINI, 50 = MINOR, 250 = MAJOR
GRAND = 1000; P_RESPIN = 0.055; TRIGGER = 6
def counts(coin=(3,3,3,3,3), wild=(0,2,2,2,0)):
    base = {'PHOENIX':2,'TIGER':3,'KOI':3,'TURTLE':4,'A':5,'K':5,'Q':5,'J':6,'TEN':6}
    return [dict(base, COIN=coin[i], WILD=wild[i]) for i in range(5)]
def strips(cnt, seed):
    rnd = random.Random(seed); out = []
    for reel in cnt:
        s = [k for k, n in reel.items() for _ in range(n)]; rnd.shuffle(s); out.append(s)
    return out
def window(s, i): L = len(s); return [s[(i+r) % L] for r in range(3)]
def exact(st, pays=PAYS, values=VALUES, p_respin=P_RESPIN):
    L = [len(s) for s in st]
    # ways
    eways = 0.0
    for t in pays:
        Em = []; P0 = []
        for c, s in enumerate(st):
            ms = [sum(1 for x in window(s, i) if x == t or x == 'WILD') for i in range(L[c])]
            Em.append(sum(ms) / L[c]); P0.append(sum(1 for m in ms if m == 0) / L[c])
        for n in range(3, 6):
            prod = 1.0
            for c in range(n): prod *= Em[c]
            if n < 5: prod *= P0[n]
            eways += pays[t][n-3] * prod
    eways /= 25
    # coins visible per reel
    cdist = []
    for c, s in enumerate(st):
        d = [0.0] * 4
        for i in range(L[c]): d[sum(1 for x in window(s, i) if x == 'COIN')] += 1 / L[c]
        cdist.append(d)
    tot = [1.0]
    for d in cdist:
        n = [0.0] * (len(tot) + 3)
        for a, pa in enumerate(tot):
            for b, pb in enumerate(d): n[a+b] += pa * pb
        tot = n
    # Hold & Win: distribution of the final coin count from each start, exact (Markov chain)
    from math import comb
    memo = {}
    def final(nc, r):
        if nc == 15: return {15: 1.0}
        if r == 0: return {nc: 1.0}
        key = (nc, r)
        if key in memo: return memo[key]
        out = {}; free = 15 - nc
        for k in range(free + 1):
            pk = comb(free, k) * p_respin**k * (1 - p_respin)**(free - k)
            sub = final(nc + k, 3) if k > 0 else final(nc, r - 1)
            for f, pf in sub.items(): out[f] = out.get(f, 0) + pk * pf
        memo[key] = out; return out
    W = sum(w for _, w in values); ev = sum(v * w for v, w in values) / W
    ehw = 0.0; trig = 0.0; pgrand = 0.0
    for nc in range(TRIGGER, len(tot)):
        if tot[nc] == 0: continue
        trig += tot[nc]
        for f, pf in final(nc, 3).items():
            ehw += tot[nc] * pf * (f * ev + (GRAND if f == 15 else 0))
            if f == 15: pgrand += tot[nc] * pf
    return dict(rtp=eways + ehw, ways=eways, holdwin=ehw, trigger=trig, grand=pgrand)
if __name__ == '__main__':
    st = strips(counts(), 11); print(exact(st), [len(s) for s in st])

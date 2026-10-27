"""Exact RTP of Pharaoh's Book from the reel strips (no simulation):
   reels stop independently and uniformly, so each line sees one independent symbol per reel with the strip's frequencies;
   books never show twice on a reel (spacing), so the book count is a sum of independent per-reel events."""
import itertools, sys, json
import pharaoh_sim as P
def per_reel_freq(st):
    return [{s: st[c].count(s) / len(st[c]) for s in P.SYM} for c in range(5)]
def visible(st, sym):
    """P(sym shows somewhere in the 3-row window) per reel."""
    out = []
    for s in st:
        L = len(s); out.append(sum(1 for i in range(L) if sym in (s[i], s[(i+1)%L], s[(i+2)%L])) / L)
    return out
def dist_count(ps):
    d = [1.0]
    for p in ps:
        n = [0.0] * (len(d) + 1)
        for k, v in enumerate(d): n[k] += v * (1-p); n[k+1] += v * p
        d = n
    return d
def exact(st, pays=P.PAYS, scat=P.SCAT):
    pays = dict(pays); scat = dict(scat); P.PAYS.clear(); P.PAYS.update(pays); P.SCAT.clear(); P.SCAT.update(scat)
    f = per_reel_freq(st)
    eline = 0.0
    for combo in itertools.product(P.SYM, repeat=5):
        pr = 1.0
        for c, s in enumerate(combo):
            pr *= f[c][s]
            if pr == 0: break
        if pr: eline += pr * P.line_pay(list(combo))
    nb = dist_count([3 * st[c].count('BOOK') / len(st[c]) for c in range(5)])
    escat = sum(nb[k] * scat.get(k, 0) * 10 for k in range(6))
    q = sum(nb[k] for k in range(3, 6))
    eexp = 0.0
    for s in P.SYM[1:]:
        dk = dist_count(visible(st, s))
        eexp += sum(dk[k] * pays[s][k-3] * 10 for k in range(3, 6)) / 9
    per_spin = eline * 10 + escat                       # in line bets, base spin (total bet = 10 line bets)
    fs_spins = 10 / (1 - 10 * q)
    fs_value = fs_spins * (per_spin + eexp)
    rtp = (per_spin + q * fs_value) / 10
    return dict(rtp=rtp, base=per_spin / 10, fs=q * fs_value / 10, trigger=q, fsSpins=fs_spins, expanding=eexp / 10)
if __name__ == '__main__':
    st = P.strips(P.counts((1,1,1,1,1)), 7)
    print(exact(st))

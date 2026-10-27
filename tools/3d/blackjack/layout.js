// One table frame shared by the table model, the dealer animation baker and the app (exported to layout.json).
// Origin: floor, under the middle of the dealer's straight edge. +z points to the player, +y up. Metres.
export const L = {
  feltY: 0.80,            // top of the felt
  railY: 0.835,           // top of the padded rail
  radius: 0.95,           // outer radius of the half-round table (rail outside edge)
  railW: 0.13,            // padded rail width
  dealerZ: -0.25,         // where the dealer stands
  shoe: [0.42, 0.80, 0.13],          // card exit of the shoe (dealer's left = player's right)
  discard: [-0.47, 0.80, 0.12],
  rack: [0, 0.80, 0.07],             // chip rack centre (sunk into the table)
  dealerCards: [-0.03, 0.80, 0.21],      // dealer hand: first card here, more to the right (+x)
  playerCards: [0, 0.80, 0.565],      // player hand centre (single hand)
  playerBet: [0, 0.80, 0.70],        // betting circle
  insurance: [0, 0.80, 0.33],
  pairsBet: [-0.10, 0.80, 0.693],          // side bet circles next to the main bet
  plus3Bet: [0.10, 0.80, 0.693],        // insurance line
  splitDx: 0.17,                     // split hands sit side by side
  cardW: 0.0635, cardH: 0.0889,      // poker size 63.5 × 88.9 mm
  chipD: 0.039, chipH: 0.0033,       // 39 mm × 3.3 mm casino chips
  seatEye: [0, 1.22, 1.25],
  // Casino Hold'em on the same table
  hold: {
    dealerCards: [0, 0.80, 0.205], board: [0, 0.80, 0.352], boardDx: 0.075,
    playerCards: [0, 0.80, 0.515], ante: [0, 0.80, 0.705], call: [0, 0.80, 0.612], bonus: [-0.108, 0.80, 0.69],
  },
  // European roulette, laid out like a casino table: the wheel sunk in a wooden well at the left end, the dealer
  // standing beside it, the betting layout running along the table (zero next to the wheel), the racetrack beyond it
  roul: {
    k: 1.3,                                   // wheel scale (geometry is modelled at 0.62 m and scaled to a real 0.81 m wheel)
    wheel: [-1.08, 0.795, 0.36],              // wheel axis; y = wheel origin (rim ≈ 4 cm above the felt)
    trackR: 0.36465, trackY: 0.02665,         // ball centre while it orbits on the track (relative to the wheel origin)
    pocketR: 0.1859, pocketY: -0.00455, numberR: 0.2301, ballR: 0.011,
    defR: 0.312, defY: 0.0156,
    dealer: [-0.70, -0.27],                   // where the croupier stands (x, z)
    table: { xl: -1.55, xr: 1.30, zb: -0.12, zf: 1.02, well: -0.62, corner: 0.26, rail: 0.11 },
    grid: { gx0: -0.448, gz0: 0.50, cw: 0.074, ch: 0.084, dh: 0.064, eh: 0.064 },
    race: { cx: 0.50, cz: 0.27, hl: 0.36, rOut: 0.14, rIn: 0.085 },
    rack: [-0.42, 0.80, 0.07], marquee: [-1.45, 0.80, -0.06], sign: [1.12, 0.80, 0.0],
    seatEye: [0, 1.30, 1.50],
  },
  // Baccarat (Punto Banco): two hands side by side, bet areas in front of the player
  bacc: {
    playerHand: [-0.13, 0.80, 0.30], bankerHand: [0.13, 0.80, 0.30], cardDx: 0.07, thirdDx: 0.125,
    player: [-0.12, 0.80, 0.665], banker: [0.12, 0.80, 0.665], tie: [0, 0.80, 0.555],
    ppair: [-0.275, 0.80, 0.615], bpair: [0.275, 0.80, 0.615],
  },
};

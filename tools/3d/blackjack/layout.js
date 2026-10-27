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
  // European roulette: rectangular table, wheel in front of the dealer, betting layout toward the player
  roul: {
    wheel: [0, 0.84, 0.29],          // wheel axis; y = top of the cabinet base (sits on the felt, 4 cm high)
    trackR: 0.2805, trackY: 0.0205,  // ball centre while it orbits on the track (relative to the wheel origin)
    pocketR: 0.143, pocketY: -0.0035, numberR: 0.177, ballR: 0.0085,
    defR: 0.24, defY: 0.012,         // deflectors on the lower track
    grid: { gx0: -0.408, gz0: 0.62, cw: 0.068, ch: 0.074, dh: 0.055, eh: 0.055 },
    rack: [-0.6, 0.80, 0.10], marquee: [0.86, 0.80, 0.02],
    seatEye: [0, 1.24, 1.42],
  },
  // Baccarat (Punto Banco): two hands side by side, bet areas in front of the player
  bacc: {
    playerHand: [-0.13, 0.80, 0.30], bankerHand: [0.13, 0.80, 0.30], cardDx: 0.07, thirdDx: 0.125,
    player: [-0.12, 0.80, 0.665], banker: [0.12, 0.80, 0.665], tie: [0, 0.80, 0.555],
    ppair: [-0.275, 0.80, 0.615], bpair: [0.275, 0.80, 0.615],
  },
};

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
  },          // eye of a seated player (for previews)
};

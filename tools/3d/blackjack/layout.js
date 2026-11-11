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
  // Money wheel (big wheel game show): a 1.44 m wheel standing on a stage, the host beside it, the betting console in front
  mw: {
    wheel: [0, 1.32, 0.0], R: 0.72, face: 0.07,         // wheel axis centre, outer radius of the segments, front face z (relative)
    pegR: 0.735, flapper: [0, 2.075, 0.11],              // pegs ring radius; flapper pivot above the wheel
    host: [1.22, 0.06, 0.16], hostYaw: -28,               // host stands on the stage on the wheel's right (player's view)
    grab: [0.70, 1.55, 0.10], pull: [0.62, 1.02, 0.10],   // peg she takes and where she pulls it to
    desk: { z: 1.22, y: 0.95, w: 1.7, d: 0.42 },          // betting console top (centre z), its height, width, depth
    spots: [-0.675, -0.405, -0.135, 0.135, 0.405, 0.675], spotZ: 1.20, spotR: 0.095,
    front: 1.45, seatEye: [0, 1.28, 2.25],
  },
  // Baccarat (Punto Banco): two hands side by side, bet areas in front of the player
  bacc: {
    playerHand: [-0.13, 0.80, 0.30], bankerHand: [0.13, 0.80, 0.30], cardDx: 0.07, thirdDx: 0.125,
    player: [-0.12, 0.80, 0.665], banker: [0.12, 0.80, 0.665], tie: [0, 0.80, 0.555],
    ppair: [-0.275, 0.80, 0.615], bpair: [0.275, 0.80, 0.615],
  },
  // Higher or Lower: the cards of the round lie in one row, left to right; the pot sits between the row and the bet
  hilo: {
    row: [-0.374, 0.80, 0.36], rowDx: 0.068, places: 12,
    bet: [0, 0.80, 0.69], pot: [0, 0.80, 0.555],
    lower: [-0.25, 0.80, 0.605], higher: [0.25, 0.80, 0.605], zone: [0.088, 0.05],     // call areas: centre, half width, half depth
  },
  // Craps (Pass Line + Field) on a tub table in the same frame: padded walls on three sides, the far wall in two
  // pyramid-rubber sections with the dealer's opening and the chip bank between them. Dice fly from the player to the far wall.
  craps: {
    feltX: 0.84, zFar: 0.0, zNear: 0.99,                 // felt: half width, far edge, near edge (inner faces of the walls)
    wallT: 0.11, wallH: 0.17, gap: 0.37,                 // wall thickness, height above the felt, half width of the dealer's opening
    numbers: [4, 5, 6, 8, 9, 10], pointZ: 0.25, pointX0: -0.5, pointDx: 0.2, pointW: 0.19, pointD: 0.15,
    field: [0, 0.80, 0.60], fieldW: 1.20, fieldD: 0.16, fieldChips: [0.485, 0.80, 0.60],
    pass: [0, 0.80, 0.83], passW: 1.52, passD: 0.10, passSideZ: 0.44, passChips: [0, 0.80, 0.83],
    puckOff: [-0.70, 0.80, 0.115], puckD: 0.075,
    die: 0.019,                                           // 3/4 inch casino dice
    throwFrom: [0, 1.0, 1.02], wallZ: 0.012, hitX: [0.50, 0.74], hitY: [0.04, 0.11],       // where the dice leave the hand and where they strike the far wall
    rest: { x: 0.42, z0: 0.37, z1: 0.48 },                // they come to rest in this strip (|x| ≤ x), between the numbers and the field
    seatEye: [0, 1.55, 1.55],
  },
};

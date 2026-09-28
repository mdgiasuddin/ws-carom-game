package com.example.carromgame.game;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure game model: physics + rules. Board coordinates are 0..520 on both axes.
 * Player 1 (light coins, WHITE) shoots from the bottom, player 2 (dark, BLACK) from the top.
 * NOT thread-safe: all access must happen on the room's single thread.
 */
public class GameEngine {

    public static class Piece {
        public final int id;
        public final Kind kind;
        public double x, y, vx, vy;

        Piece(int id, Kind kind, double x, double y) {
            this.id = id;
            this.kind = kind;
            this.x = x;
            this.y = y;
        }

        double r() {
            return kind == Kind.STRIKER ? STRIKER_R : COIN_R;
        }

        double mass() {
            return kind == Kind.STRIKER ? 2.2 : 1.0;
        }
    }

    static final double BOARD = 520, C = BOARD / 2;
    static final double COIN_R = 11, STRIKER_R = 16, POCKET_R = 14, POCKET_INSET = 21;
    static final double BASELINE_INSET = 62, HALF = 170;
    static final int COINS = 9, SUBSTEPS = 8, QUEEN_BONUS = 5;
    static final double MAX_SPEED = 11.25, MIN_SPEED = 0.9, DECEL = 0.025;
    static final double CUSHION = 0.74, BOUNCE = 0.94, REST = 0.02;
    static final long TURN_NANOS = 12_000_000_000L;

    public final List<Piece> pieces = new ArrayList<>();
    public Piece striker;
    public Phase phase;
    public int turn;                       // 1 or 2
    public int[] pocketed = new int[3];    // index 1, 2
    public int queenOwner, queenPending;   // 0 = nobody
    public boolean queenOnBoard;
    public int winner;
    public String message = "";
    public long deadline;                  // System.nanoTime(); 0 = none

    private int nextId;
    private final List<Kind> potted = new ArrayList<>();
    private boolean strikerPotted;

    public GameEngine() {
        reset();
    }

    // ------------------------------------------------------------------ setup
    public void reset() {
        pieces.clear();
        nextId = 1;
        pocketed = new int[3];
        queenOwner = 0;
        queenPending = 0;
        queenOnBoard = true;
        winner = 0;
        turn = 1;
        phase = Phase.READY;
        potted.clear();
        strikerPotted = false;
        pieces.add(new Piece(nextId++, Kind.QUEEN, C, C));
        ring(6, 2 * COIN_R, 0);
        ring(12, 4 * COIN_R, Math.PI / 12);
        striker = null;
        placeStrikerForTurn();
        message = "Player 1 to break";
        deadline = System.nanoTime() + TURN_NANOS;
    }

    private void ring(int count, double radius, double offset) {
        for (int i = 0; i < count; i++) {
            double a = offset + 2 * Math.PI * i / count;
            pieces.add(new Piece(nextId++, i % 2 == 0 ? Kind.LIGHT : Kind.DARK,
                    C + radius * Math.cos(a), C + radius * Math.sin(a)));
        }
    }

    static Kind coin(int player) {
        return player == 1 ? Kind.LIGHT : Kind.DARK;
    }

    static double baselineY(int player) {
        return player == 1 ? BOARD - BASELINE_INSET : BASELINE_INSET;
    }

    static double[][] pockets() {
        double a = POCKET_INSET, b = BOARD - POCKET_INSET;
        return new double[][]{{a, a}, {b, a}, {a, b}, {b, b}};
    }

    public int score(int p) {
        return pocketed[p] + (queenOwner == p ? QUEEN_BONUS : 0);
    }

    // ------------------------------------------------------------------ commands
    public boolean canAct(int player) {
        return phase == Phase.READY && turn == player;
    }

    /**
     * Returns the legal x the striker ended up at, or NaN if the command was rejected.
     */
    public double place(int player, double x) {
        if (!canAct(player) || !Double.isFinite(x)) return Double.NaN;
        striker.x = nearestLegalX(x, baselineY(turn));
        striker.y = baselineY(turn);
        striker.vx = striker.vy = 0;
        return striker.x;
    }

    public boolean shoot(int player, double x, double dx, double dy, double power) {
        if (!canAct(player) || !Double.isFinite(x + dx + dy + power)) return false;
        double len = Math.sqrt(dx * dx + dy * dy);
        if (len < 1e-6) return false;
        dx /= len;
        dy /= len;
        // Clamp to the forward half-board so nobody shoots backwards off their own baseline.
        double fwd = turn == 1 ? -1 : 1, maxA = Math.toRadians(80);
        if (dy * fwd < Math.cos(maxA)) {
            dx = (dx >= 0 ? 1 : -1) * Math.sin(maxA);
            dy = fwd * Math.cos(maxA);
        }
        double speed = Math.min(1, Math.max(0, power)) * MAX_SPEED;
        if (speed < MIN_SPEED) return false;

        place(player, x);
        striker.vx = dx * speed;
        striker.vy = dy * speed;
        potted.clear();
        strikerPotted = false;
        phase = Phase.SHOOTING;
        deadline = 0;
        message = "";
        return true;
    }

    /**
     * Called every tick while READY. Returns true if the turn was forfeited.
     */
    public boolean checkTimeout(long now) {
        if (phase != Phase.READY || deadline == 0 || now < deadline) return false;
        message = "Player " + turn + " ran out of time - turn passes";
        turn = 3 - turn;
        placeStrikerForTurn();
        deadline = now + TURN_NANOS;
        return true;
    }

    public long millisLeft(long now) {
        return (phase == Phase.READY && deadline != 0) ? Math.max(0, (deadline - now) / 1_000_000) : -1;
    }

    // ------------------------------------------------------------------ physics

    /**
     * Exactly one 60fps-equivalent frame, split into fixed sub-steps.
     */
    public void step() {
        double dt = 1.0 / SUBSTEPS;
        for (int s = 0; s < SUBSTEPS; s++) {
            for (Piece p : pieces) {
                p.x += p.vx * dt;
                p.y += p.vy * dt;
                cushion(p);
            }
            for (int i = 0; i < pieces.size(); i++)
                for (int j = i + 1; j < pieces.size(); j++) collide(pieces.get(i), pieces.get(j));
            friction(DECEL * dt);
            collectPocketed();
        }
    }

    public boolean atRest() {
        for (Piece p : pieces) if (Math.hypot(p.vx, p.vy) > REST) return false;
        return true;
    }

    private void friction(double slow) {
        for (Piece p : pieces) {
            double sp = Math.sqrt(p.vx * p.vx + p.vy * p.vy);
            if (sp <= slow) {
                p.vx = 0;
                p.vy = 0;
            } else {
                double k = (sp - slow) / sp;
                p.vx *= k;
                p.vy *= k;
            }
        }
    }

    private void cushion(Piece p) {
        double min = p.r(), max = BOARD - p.r();
        if (p.x < min) {
            p.x = min;
            p.vx = Math.abs(p.vx) * CUSHION;
        } else if (p.x > max) {
            p.x = max;
            p.vx = -Math.abs(p.vx) * CUSHION;
        }
        if (p.y < min) {
            p.y = min;
            p.vy = Math.abs(p.vy) * CUSHION;
        } else if (p.y > max) {
            p.y = max;
            p.vy = -Math.abs(p.vy) * CUSHION;
        }
    }

    private void collide(Piece a, Piece b) {
        double dx = b.x - a.x, dy = b.y - a.y, dist = Math.sqrt(dx * dx + dy * dy);
        double min = a.r() + b.r();
        if (dist >= min) return;
        if (dist < 1e-6) {
            dx = 1;
            dy = 0;
            dist = 1;
        }
        double nx = dx / dist, ny = dy / dist;
        double invA = 1 / a.mass(), invB = 1 / b.mass(), overlap = min - dist;
        a.x -= nx * overlap * invA / (invA + invB);
        a.y -= ny * overlap * invA / (invA + invB);
        b.x += nx * overlap * invB / (invA + invB);
        b.y += ny * overlap * invB / (invA + invB);
        double rel = (a.vx - b.vx) * nx + (a.vy - b.vy) * ny;
        if (rel <= 0) return;
        double imp = (1 + BOUNCE) * rel / (invA + invB);
        a.vx -= imp * invA * nx;
        a.vy -= imp * invA * ny;
        b.vx += imp * invB * nx;
        b.vy += imp * invB * ny;
    }

    private void collectPocketed() {
        for (int i = pieces.size() - 1; i >= 0; i--) {
            Piece p = pieces.get(i);
            boolean in = false;
            for (double[] c : pockets()) if (Math.hypot(p.x - c[0], p.y - c[1]) < POCKET_R - 2) in = true;
            if (!in) continue;
            pieces.remove(i);
            if (p.kind == Kind.STRIKER) strikerPotted = true;
            else {
                potted.add(p.kind);
                if (p.kind == Kind.QUEEN) queenOnBoard = false;
            }
        }
    }

    // ------------------------------------------------------------------ rules

    /**
     * Call once the board is at rest after a shot.
     */
    public void resolveShot() {
        int me = turn, rv = 3 - turn;
        Kind mine = coin(me);
        int own = 0, riv = 0;
        boolean queen = false;
        for (Kind k : potted) {
            if (k == Kind.QUEEN) queen = true;
            else if (k == mine) own++;
            else riv++;
        }
        pocketed[me] += own;
        pocketed[rv] += riv;
        String who = "Player " + me;
        boolean again;

        if (strikerPotted) {
            int owed = 1;
            again = own > 0;
            if (own > 0) owed += own;
            for (int i = 0; i < riv; i++) spawn(coin(rv));
            pocketed[rv] -= riv;
            int ret = Math.min(pocketed[me], owed);
            pocketed[me] -= ret;
            for (int i = 0; i < ret; i++) spawn(mine);
            restoreQueen();
            message = who + " pocketed the striker (foul). " + ret + " coin(s) returned.";
        } else {
            if (queen) {
                if (own > 0 && riv == 0) {
                    queenOwner = me;
                    queenPending = 0;
                    again = true;
                    message = who + " pocketed and covered the Queen!";
                } else if (own > 0) {
                    queenOwner = me;
                    queenPending = 0;
                    again = false;
                    message = who + " covered the Queen but pocketed a rival coin.";
                } else if (riv > 0) {
                    restoreQueen();
                    again = false;
                    message = who + " pocketed Queen and rival coin. Queen returns.";
                } else {
                    queenPending = me;
                    again = true;
                    message = who + " pocketed the Queen. Must cover next shot.";
                }
            } else if (queenPending == me) {
                if (own > 0 && riv == 0) {
                    queenOwner = me;
                    queenPending = 0;
                    again = true;
                    message = "Queen covered by " + who;
                } else {
                    restoreQueen();
                    again = false;
                    message = who + " failed to cover. Queen returns.";
                }
            } else if (own > 0 && riv == 0) {
                again = true;
                message = who + " scores and continues.";
            } else if (riv > 0) {
                again = false;
                message = who + " pocketed a rival coin. Turn passes.";
            } else {
                again = false;
                message = "No coins pocketed. Turn passes.";
            }

            if (pocketed[me] >= COINS && queenOwner == 0) {
                restoreQueen();
                if (pocketed[me] > 0) {
                    pocketed[me]--;
                    spawn(mine);
                }
                again = false;
                message = who + " cleared all coins before securing the Queen! Penalty: 1 coin returned.";
            }
        }

        // Potting the rival's last coin hands them the board, so check the rival first.
        if (pocketed[rv] >= COINS) winner = rv;
        else if (pocketed[me] >= COINS) winner = me;

        if (winner != 0) {
            phase = Phase.GAME_OVER;
            deadline = 0;
            message = "Player " + winner + " wins!";
            if (striker == null || !pieces.contains(striker)) {
                striker = null;
                placeStrikerForTurn();
            }
            return;
        }
        if (!again) turn = rv;
        phase = Phase.READY;
        placeStrikerForTurn();
        deadline = System.nanoTime() + TURN_NANOS;
    }

    private void restoreQueen() {
        queenPending = 0;
        if (!queenOnBoard && queenOwner == 0 && spawn(Kind.QUEEN)) queenOnBoard = true;
    }

    private boolean spawn(Kind kind) {
        for (double r = 0; r <= BOARD / 2 - COIN_R; r += COIN_R) {
            int n = r < 1 ? 1 : (int) Math.max(8, 2 * Math.PI * r / COIN_R);
            for (int i = 0; i < n; i++) {
                double a = 2 * Math.PI * i / n, x = C + r * Math.cos(a), y = C + r * Math.sin(a);
                if (spotFree(x, y)) {
                    pieces.add(new Piece(nextId++, kind, x, y));
                    return true;
                }
            }
        }
        return false;
    }

    private boolean spotFree(double x, double y) {
        if (x - COIN_R < 4 || x + COIN_R > BOARD - 4 || y - COIN_R < 4 || y + COIN_R > BOARD - 4) return false;
        for (double[] c : pockets()) if (Math.hypot(x - c[0], y - c[1]) < POCKET_R + COIN_R + 8) return false;
        for (Piece p : pieces) if (Math.hypot(x - p.x, y - p.y) < COIN_R + p.r() + 1.5) return false;
        return true;
    }

    private void placeStrikerForTurn() {
        double y = baselineY(turn);
        if (striker == null || !pieces.contains(striker)) {
            striker = new Piece(0, Kind.STRIKER, C, y);
            pieces.add(striker);
        }
        striker.vx = striker.vy = 0;
        striker.y = y;
        striker.x = nearestLegalX(C, y);
    }

    private double nearestLegalX(double desired, double y) {
        double lo = C - HALF + STRIKER_R, hi = C + HALF - STRIKER_R;
        double t = Math.min(hi, Math.max(lo, desired));
        if (clear(t, y)) return t;
        for (double off = 2; off <= hi - lo; off += 2)
            for (int side = -1; side <= 1; side += 2) {
                double c = t + side * off;
                if (c >= lo && c <= hi && clear(c, y)) return c;
            }
        return t;
    }

    private boolean clear(double x, double y) {
        for (Piece p : pieces)
            if (p != striker && Math.hypot(x - p.x, y - p.y) < STRIKER_R + p.r() + 0.5) return false;
        return true;
    }
}

package com.example.carromgame.game;

import static com.example.carromgame.game.GameEngine.COIN_R;
import static com.example.carromgame.game.GameEngine.STRIKER_R;

public class Piece {
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

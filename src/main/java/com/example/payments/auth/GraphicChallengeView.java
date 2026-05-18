package com.example.payments.auth;

public record GraphicChallengeView(
        String challengeId,
        String image,
        int width,
        int height,
        int minX,
        int maxX,
        String pieceImage,
        int pieceY,
        int pieceSize
) {
}

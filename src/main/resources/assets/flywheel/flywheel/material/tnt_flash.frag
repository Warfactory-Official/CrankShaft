// SPDX-FileCopyrightText: 2021-2024 Jozufozu
// SPDX-License-Identifier: MIT
// CrankShaft addition (not from upstream Flywheel): 1.12.2-specific TNT minecart flash overlay.
// Mirrors vanilla RenderTntMinecart's second pass which disabled GL_TEXTURE_2D and drew the
// cargo with glColor4f(1,1,1,alpha) + SRC_ALPHA*DST_ALPHA additive blend. Upstream Flywheel
// gets the same effect via 1.16+'s built-in overlayTex sampler; 1.12.2 has no equivalent
// system so we keep a literal second draw + custom fragment that outputs white.
//
// Sampled alpha keeps cutout-style transparent texels invisible (they don't write color in
// the first pass, so the second pass shouldn't either).
void flw_materialFragment() {
    flw_fragColor = vec4(1.0, 1.0, 1.0, flw_sampleColor.a * flw_vertexColor.a);
}

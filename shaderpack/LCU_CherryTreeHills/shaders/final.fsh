#version 120
// FXAA (after the tone curve): smooths stair-stepped edges of the city's geometry, railings and roof lines.
// Based on the public FXAA 3.11 "quality" algorithm by Timothy Lottes, reduced to its edge search.
uniform sampler2D colortex0; // rgb = final colour, a = luma (composite1)
uniform float viewWidth;
uniform float viewHeight;
varying vec2 texcoord;

#define FXAA 1 // [0 1]
#define FXAA_EDGE_MIN 0.0312
#define FXAA_EDGE_MAX 0.125
#define FXAA_SUBPIX 0.75

float luma(vec2 uv) { return texture2D(colortex0, uv).a; }

void main() {
	vec4 c = texture2D(colortex0, texcoord);
#if FXAA == 1
	vec2 px = vec2(1.0 / viewWidth, 1.0 / viewHeight);
	float m = c.a;
	float n = luma(texcoord + vec2(0.0, px.y)), s = luma(texcoord - vec2(0.0, px.y));
	float e = luma(texcoord + vec2(px.x, 0.0)), w = luma(texcoord - vec2(px.x, 0.0));
	float hi = max(max(n, s), max(max(e, w), m)), lo = min(min(n, s), min(min(e, w), m));
	float range = hi - lo;
	if (range >= max(FXAA_EDGE_MIN, hi * FXAA_EDGE_MAX)) {
		float ne = luma(texcoord + px), sw = luma(texcoord - px);
		float nw = luma(texcoord + vec2(-px.x, px.y)), se = luma(texcoord + vec2(px.x, -px.y));
		// sub-pixel aliasing amount
		float avg = (2.0 * (n + s + e + w) + ne + nw + se + sw) / 12.0;
		float sub = clamp(abs(avg - m) / range, 0.0, 1.0);
		sub = smoothstep(0.0, 1.0, sub);
		sub = sub * sub * FXAA_SUBPIX;
		// edge direction
		float edgeH = abs(nw + ne - 2.0 * n) + 2.0 * abs(w + e - 2.0 * m) + abs(sw + se - 2.0 * s);
		float edgeV = abs(nw + sw - 2.0 * w) + 2.0 * abs(n + s - 2.0 * m) + abs(ne + se - 2.0 * e);
		bool horizontal = edgeH >= edgeV;
		float stp = horizontal ? px.y : px.x;
		float l1 = horizontal ? n : e, l2 = horizontal ? s : w;
		float g1 = abs(l1 - m), g2 = abs(l2 - m);
		if (g2 > g1) stp = -stp;
		float edgeLuma = 0.5 * (m + (g2 > g1 ? l2 : l1));
		float grad = 0.25 * max(g1, g2);
		vec2 uv = texcoord;
		if (horizontal) uv.y += stp * 0.5; else uv.x += stp * 0.5;
		vec2 dir = horizontal ? vec2(px.x, 0.0) : vec2(0.0, px.y);
		// walk along the edge in both directions until it ends
		vec2 p1 = uv - dir, p2 = uv + dir;
		float d1 = luma(p1) - edgeLuma, d2 = luma(p2) - edgeLuma;
		bool done1 = abs(d1) >= grad, done2 = abs(d2) >= grad;
		for (int i = 0; i < 10; i++) {
			if (done1 && done2) break;
			float k = i < 4 ? 1.0 : 2.0;
			if (!done1) { p1 -= dir * k; d1 = luma(p1) - edgeLuma; done1 = abs(d1) >= grad; }
			if (!done2) { p2 += dir * k; d2 = luma(p2) - edgeLuma; done2 = abs(d2) >= grad; }
		}
		float dist1 = horizontal ? texcoord.x - p1.x : texcoord.y - p1.y;
		float dist2 = horizontal ? p2.x - texcoord.x : p2.y - texcoord.y;
		bool closer1 = dist1 < dist2;
		float dEnd = closer1 ? d1 : d2;
		float off = 0.0;
		if ((m - edgeLuma < 0.0) != (dEnd < 0.0)) {
			off = 0.5 - min(dist1, dist2) / (dist1 + dist2);
		}
		off = max(off, sub);
		vec2 fuv = texcoord;
		if (horizontal) fuv.y += off * stp; else fuv.x += off * stp;
		c.rgb = texture2D(colortex0, fuv).rgb;
	}
#endif
	gl_FragColor = vec4(c.rgb, 1.0);
}

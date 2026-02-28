#version 150

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <minecraft:globals.glsl>

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV2;

uniform sampler2D Sampler2;

out float sphericalVertexDistance;
out float cylindricalVertexDistance;
out vec4 vertexColor;
out vec2 texCoord0;

void main() {
    // Calculate UI coordinate system
    vec2 ui = vec2(ScreenSize.x / ScreenSize.y, 1.0);
    vec2 uiScreen = vec2(ScreenSize.x, ScreenSize.y);
    // Make position mutable
    vec3 pos = Position;
    vec4 col = Color;
    bool isHudElement = false;
    bool isShadow = false;  
    // Detect and reposition HUD elements, NOTE that we are okay with this 
    // clobbering other plugins and normal game text for now (its in development)
    if (pos.y < 100.0 && ProjMat[3].x == -1.0) {
        isHudElement = true;
        // This code detects and removes the drop shadow effect
        // that is applied to BossBar titles
        float brightness = (Color.r + Color.g + Color.b) / 3.0;
        isShadow = brightness <= 0.247;
        if (brightness <= 0.247) {
            col.a = 0.0;
        }
        // Apply positional offset to reposition glyph quad
        // of hud elements
        if (!isShadow) {
            int gridX = int((Color.r * 255.0 - 64.0) / 5.0 + 0.5);
            int gridY = int((Color.g * 255.0 - 64.0) / 5.0 + 0.5);
            // Clamp to valid range
            gridX = clamp(gridX, 0, 31);
            gridY = clamp(gridY, 0, 31);
            pos.x += gridX * 10.0;
            pos.y += gridY * 10.0;
            col.r = 1.0;
            col.g = 1.0;
            col.b = 1.0;
            col.a = 1.0;
        } else {
            col.a = 0.0;
        }
    }
    vec3 finalPos = isHudElement ? pos : Position;
    gl_Position = ProjMat * ModelViewMat * vec4(finalPos, 1.0);
    vertexColor = col * texelFetch(Sampler2, UV2 / 16, 0);
    sphericalVertexDistance = fog_spherical_distance(finalPos);
    cylindricalVertexDistance = fog_cylindrical_distance(finalPos);
    texCoord0 = UV0;
}
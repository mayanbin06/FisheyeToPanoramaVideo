uniform mat4 uMVPMatrix;
uniform mat4 uTextureMatrix;
attribute vec4 aPosition;
attribute vec4 aTextureCoord;
varying vec2 vTextureCoord;

void main() {
    //gl_Position = uMVPMatrix * aPosition * vec4(1, -1, 1, 1); // vec4 纠正上下颠倒 ???
    gl_Position = uMVPMatrix * aPosition;
    vTextureCoord = (uTextureMatrix * aTextureCoord).xy; // SurfaceTexture 采样修正矩阵, 直接使用自定义的uv...
    vTextureCoord = (aTextureCoord).xy;
}

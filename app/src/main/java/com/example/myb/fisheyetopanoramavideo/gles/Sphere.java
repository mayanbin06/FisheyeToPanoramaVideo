package com.example.myb.fisheyetopanoramavideo.gles;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;

public class Sphere {
    public static final int FLOAT_SIZE = 4;
    public static final int SHORT_SIZE = 2;
    private FloatBuffer mVertices;
    // 将Indice分开管理, mTotalIndices 总的Indices顶点数 (当前例子只有1组)
    //                 mNumIndices[i] 第i组Indices顶点数
    //                 mIndices[i] 第i组Indices顶点的Buffer
    private ShortBuffer[] mIndices;
    private int[] mNumIndices;
    private int mTotalIndices;
    /*
     * @param nSlices how many slice in horizontal direction.
     *                The same slice for vertical direction is applied.
     *                nSlices should be > 1 and should be <= 180
     * @param x,y,z the origin of the sphere
     * @param r the radius of the sphere
     */
    public Sphere(int nSlices, float x, float y, float z, float r, int numIndexBuffers) {
        int iMax = nSlices + 1;
        int nVertices = iMax * iMax;
        if (nVertices > Short.MAX_VALUE) {
            // this cannot be handled in one vertices / indices pair
            throw new RuntimeException("nSlices " + nSlices + " too big for vertex");
        }
        mTotalIndices = nSlices * nSlices * 6;
        float angleStepI = ((float) Math.PI / nSlices);
        float angleStepJ = ((2.0f * (float) Math.PI) / nSlices);
        // 3 vertex coords + 2 texture coords
        mVertices = ByteBuffer.allocateDirect(nVertices * 5 * FLOAT_SIZE)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        mIndices = new ShortBuffer[numIndexBuffers];
        mNumIndices = new int[numIndexBuffers];
        // first evenly distribute to n-1 buffers, then put remaining ones to the last one.
        int noIndicesPerBuffer = (mTotalIndices / numIndexBuffers / 6) * 6;
        for (int i = 0; i < numIndexBuffers - 1; i++) {
            mNumIndices[i] = noIndicesPerBuffer;
        }
        mNumIndices[numIndexBuffers - 1] = mTotalIndices - noIndicesPerBuffer *
                (numIndexBuffers - 1);
        for (int i = 0; i < numIndexBuffers; i++) {
            mIndices[i] = ByteBuffer.allocateDirect(mNumIndices[i] * SHORT_SIZE)
                    .order(ByteOrder.nativeOrder()).asShortBuffer();
        }
        // calling put for each float took too much CPU time, so put by line instead
        float[] vLineBuffer = new float[iMax * 5];
        // 将i(angleStepI为步长, i = n时, 则为PI)看成与z轴正向的夹角, j是与x轴正向的夹角,
        // 则任意一个i和j即可以确定一点 (x = r*sin(i)*sin(j),y = r*sin(i)*cos(j),z = r*cos(i))
        for (int i = 0; i < iMax; i++) { // i 纬度
            for (int j = 0; j < iMax; j++) { // j 经度
                int vertexBase = j * 5;
                float sini = (float) Math.sin(angleStepI * i);
                float sinj = (float) Math.sin(angleStepJ * j);
                float cosi = (float) Math.cos(angleStepI * i);
                float cosj = (float) Math.cos(angleStepJ * j);
                // vertex x,y,z
                vLineBuffer[vertexBase + 0] = x + r * sini * sinj;
                vLineBuffer[vertexBase + 1] = y + r * sini * cosj;
                vLineBuffer[vertexBase + 2] = z + r * cosi;

                // android txeture s,t. s-->x轴正向, t-->y轴负方向. 左上角为原点
                //CacPanoramaUV(vLineBuffer, vertexBase, i, j, nSlices);
                CalcFishEyeUVToPanorama(vLineBuffer, vertexBase, j, iMax);
            }
            mVertices.put(vLineBuffer, 0, vLineBuffer.length);
        }
        short[] indexBuffer = new short[max(mNumIndices)];
        int index = 0;
        int bufferNum = 0;
        for (int i = 0; i < nSlices; i++) {
            for (int j = 0; j < nSlices; j++) {
                int i1 = i + 1;
                int j1 = j + 1;
                if (index >= mNumIndices[bufferNum]) {
                    // buffer ready for moving to target
                    mIndices[bufferNum].put(indexBuffer, 0, mNumIndices[bufferNum]);
                    // move to the next one
                    index = 0;
                    bufferNum++;
                }
                indexBuffer[index++] = (short) (i * iMax + j);
                indexBuffer[index++] = (short) (i1 * iMax + j);
                indexBuffer[index++] = (short) (i1 * iMax + j1);
                indexBuffer[index++] = (short) (i * iMax + j);
                indexBuffer[index++] = (short) (i1 * iMax + j1);
                indexBuffer[index++] = (short) (i * iMax + j1);
            }
        }
        mIndices[bufferNum].put(indexBuffer, 0, mNumIndices[bufferNum]);
        mVertices.position(0);
        for (int i = 0; i < numIndexBuffers; i++) {
            mIndices[i].position(0);
        }
    }

    // equirectangular projection
    // 适用于 equirectangular texture 全景视频.
    private void CacPanoramaUV(float[] vLineBuffer, int vertexBase, int i, int j, int nSlices) {
        // texture s,t, 圆柱的映射方式
        vLineBuffer[vertexBase + 3] = (float) j / (float) nSlices;
        // 这里为负数, 则 glsl中, uv = textureMatrix * uv, 使用SurfaceTexture矩阵修正.
        //vLineBuffer[vertexBase + 4] = (-i) / (float) nSlices;
        vLineBuffer[vertexBase + 4] = (float) i / (float) nSlices; // 直接使用

    }

    // 双fisheye to equirectangular 全景视频.
    //      | z
    //      |
    //      |
    //      |____________ y
    //      /
    //     /
    //    /
    //    x x轴朝屏幕里面为正值, z和y的平面组成投影, 即屏幕显示区域.
    private void CalcFishEyeUVToPanorama(float[] vLineBuffer, int vertexBase, int j, int n) {
        float x = vLineBuffer[vertexBase + 0];
        float y = vLineBuffer[vertexBase + 1];
        float z = vLineBuffer[vertexBase + 2];

        float u, v;
        double r, phi, theta; // r 为鱼眼图内半径
        double R = 0.48; // v 轴方向半径, u轴方向半径再除以2... 此值为估计值,实际应该用算法取得鱼眼图的半径.
        //  r = f * theta 等距投影法(大部分鱼眼相机), theta为鱼眼镜头半视场角, 一般视场角 PI, theta = PI / 2
        double f = R / Math.PI * 2; // 鱼眼相机成像, 等距投影公式 r = f * theta; 取R为鱼眼圆的最大半径, 计算出f.
        //  r =  2 * f * tan(theta/2) -- 体视投影
        //f = R / (2 * Math.tan(Math.PI/4));
        //  r =  2 * f * sin(theta/2) -- 等立体角投影
        //f = R / (2 * Math.sin(Math.PI/4));
        //  r =  f * sin(theta) -- 正交投影
        //f = R / Math.sin(Math.PI / 2);
        if (j < n / 2) { // 左边的鱼眼图 [0-180"], 左半球,x为正值

            theta = Math.atan2(Math.sqrt(z*z + y*y), x); // 计算theta
            r = f * theta; // 计算投影的半径.
            phi = Math.atan2(z, y);

            u = (float) (r * Math.cos(phi) / 2 + 0.25); // 由于双鱼眼图为长方形, U轴方向的 半径为 1/4
            v = (float) (r * Math.sin(phi) + 0.5);

            u = 0.5f - u;
            v = 1.0f - v; // android texture 坐标左上角为(0,0), 也可在glsl中修正颠倒.

        } else { // 右边的鱼眼图, 右半球, x为负值

            theta = Math.atan2(Math.sqrt(z*z + y*y), -x); // 计算theta, 此时x为负值, 计算出v为负值, 所以将x转化成正直计算.
            r = f * theta; // 计算投影的半径.
            phi = Math.atan2(z, y);

            u = (float) (r * Math.cos(phi) / 2 + 0.75);
            v = (float) (r * Math.sin(phi) + 0.5);

            //u = 1.5f - u;
            v = 1.0f - v;
            // 方法二. 算法与左边图的一致, 所以取左边的值加上0.5即可(左右切成两半,长度为texture的0.5)
            // u = vLineBuffer[(j - n / 2) * 5 + 3] + 0.5f;
            // v = vLineBuffer[(j - n / 2) * 5 + 4];
        }

        vLineBuffer[vertexBase + 3] = u;
        vLineBuffer[vertexBase + 4] = v;
    }

    public FloatBuffer getVertices() {
        return mVertices;
    }
    public int getVerticesStride() {
        return 5*FLOAT_SIZE;
    }
    public ShortBuffer[] getIndices() {
        return mIndices;
    }
    public int[] getNumIndices() {
        return mNumIndices;
    }
    public int getTotalIndices() {
        return mTotalIndices;
    }
    private int max(int[] array) {
        int max = array[0];
        for (int i = 1; i < array.length; i++) {
            if (array[i] > max) max = array[i];
        }
        return max;
    }
}

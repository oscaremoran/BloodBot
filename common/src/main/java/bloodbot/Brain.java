package bloodbot;

import arc.files.Fi;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.Random;

/**
 * The bot's neural network, stored as one flat parameter array so evolution can treat the whole brain as a
 * single vector. Three small convolution layers read the G x G map picture (each halves it: G -> G/2 -> G/4 -> G/8),
 * then two dense layers combine that with the summary numbers, and a linear layer gives every action output.
 * Plain Java so it can ship inside a Mindustry mod unchanged.
 */
public class Brain{
    /** Shape: {channels, grid, conv1, conv2, conv3, scalars, hidden, outputs}. */
    final int[] shape;
    final float[] params;

    Brain(int[] shape, float[] params){
        this.shape = shape;
        this.params = params;
        if(params.length != paramCount(shape)) throw new IllegalArgumentException("expected " + paramCount(shape) + " params, got " + params.length);
    }

    static int paramCount(int[] s){
        int c = s[0], g = s[1], k1 = s[2], k2 = s[3], k3 = s[4], sc = s[5], h = s[6], out = s[7];
        int flat = k3 * (g / 8) * (g / 8);
        return (k1 * c * 9 + k1) + (k2 * k1 * 9 + k2) + (k3 * k2 * 9 + k3)
            + (h * (flat + sc) + h) + (h * h + h) + (out * h + out);
    }

    /** Random starting brain: weights scaled by fan-in, zero biases. */
    static Brain random(int[] s, long seed){
        Random rand = new Random(seed);
        float[] p = new float[paramCount(s)];
        int c = s[0], g = s[1], k1 = s[2], k2 = s[3], k3 = s[4], sc = s[5], h = s[6], out = s[7];
        int flat = k3 * (g / 8) * (g / 8);
        int[][] blocks = {{k1 * c * 9, c * 9, k1}, {k2 * k1 * 9, k1 * 9, k2}, {k3 * k2 * 9, k2 * 9, k3},
            {h * (flat + sc), flat + sc, h}, {h * h, h, h}, {out * h, h, out}};
        int i = 0;
        for(int[] b : blocks){
            float scale = (float)(1.0 / Math.sqrt(b[1]));
            for(int w = 0; w < b[0]; w++) p[i++] = (float)rand.nextGaussian() * scale;
            i += b[2];
        }
        return new Brain(s, p);
    }

    float[] forward(float[] grid, float[] scalars){
        int c = shape[0], g = shape[1], k1 = shape[2], k2 = shape[3], k3 = shape[4], sc = shape[5], h = shape[6], out = shape[7];
        int[] at = {0};
        float[] a1 = conv(grid, c, g, k1, at);
        float[] a2 = conv(a1, k1, g / 2, k2, at);
        float[] a3 = conv(a2, k2, g / 4, k3, at);

        float[] x = new float[a3.length + sc];
        System.arraycopy(a3, 0, x, 0, a3.length);
        System.arraycopy(scalars, 0, x, a3.length, sc);
        float[] d1 = dense(x, h, at, true);
        float[] d2 = dense(d1, h, at, true);
        return dense(d2, out, at, false);
    }

    /** 3x3 convolution, stride 2, padding 1, tanh. Input [cin][n][n] -> output [cout][n/2][n/2]. */
    float[] conv(float[] in, int cin, int n, int cout, int[] at){
        int m = n / 2, w = at[0], b = w + cout * cin * 9;
        float[] o = new float[cout * m * m];
        for(int co = 0; co < cout; co++){
            for(int oy = 0; oy < m; oy++){
                for(int ox = 0; ox < m; ox++){
                    float sum = params[b + co];
                    for(int ci = 0; ci < cin; ci++){
                        int kw = w + (co * cin + ci) * 9, plane = ci * n * n;
                        for(int ky = 0; ky < 3; ky++){
                            int iy = oy * 2 + ky - 1;
                            if(iy < 0 || iy >= n) continue;
                            for(int kx = 0; kx < 3; kx++){
                                int ix = ox * 2 + kx - 1;
                                if(ix < 0 || ix >= n) continue;
                                sum += params[kw + ky * 3 + kx] * in[plane + iy * n + ix];
                            }
                        }
                    }
                    o[co * m * m + oy * m + ox] = (float)Math.tanh(sum);
                }
            }
        }
        at[0] = b + cout;
        return o;
    }

    float[] dense(float[] in, int nout, int[] at, boolean activate){
        int nin = in.length, w = at[0], b = w + nin * nout;
        float[] o = new float[nout];
        for(int j = 0; j < nout; j++){
            float sum = params[b + j];
            int row = w + j * nin;
            for(int k = 0; k < nin; k++) sum += params[row + k] * in[k];
            o[j] = activate ? (float)Math.tanh(sum) : sum;
        }
        at[0] = b + nout;
        return o;
    }

    void save(Fi file){
        try(DataOutputStream out = new DataOutputStream(file.write(false, 1 << 16))){
            out.writeInt(shape.length);
            for(int n : shape) out.writeInt(n);
            for(float v : params) out.writeFloat(v);
        }catch(Exception e){
            throw new RuntimeException(e);
        }
    }

    static Brain load(Fi file){
        try(DataInputStream in = new DataInputStream(file.read(1 << 16))){
            int[] shape = new int[in.readInt()];
            for(int i = 0; i < shape.length; i++) shape[i] = in.readInt();
            float[] p = new float[paramCount(shape)];
            for(int i = 0; i < p.length; i++) p[i] = in.readFloat();
            return new Brain(shape, p);
        }catch(Exception e){
            throw new RuntimeException(e);
        }
    }
}

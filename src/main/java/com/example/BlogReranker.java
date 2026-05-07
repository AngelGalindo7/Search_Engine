package com.example;

import ai.djl.huggingface.translator.TextEmbeddingTranslatorFactory;
import ai.djl.inference.Predictor;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.translate.TranslateException;

/**
 * Local dense-embedding reranker. Loads sentence-transformers/all-MiniLM-L6-v2
 * via DJL's PyTorch engine on first use; outputs are 384-dim L2-normalized so
 * cosine similarity reduces to a dot product at query time.
 *
 * Stateless after load: one ZooModel + Predictor pair are reused across the JVM.
 */
public final class BlogReranker {

    public static final String MODEL_URL =
            "djl://ai.djl.huggingface.pytorch/sentence-transformers/all-MiniLM-L6-v2";
    public static final int EMBEDDING_DIM = 384;

    private static volatile ZooModel<String, float[]> model;
    private static volatile Predictor<String, float[]> predictor;

    private BlogReranker() {}

    public static synchronized void load() throws Exception {
        if (predictor != null) return;
        Criteria<String, float[]> criteria = Criteria.builder()
                .setTypes(String.class, float[].class)
                .optModelUrls(MODEL_URL)
                .optEngine("PyTorch")
                .optTranslatorFactory(new TextEmbeddingTranslatorFactory())
                .build();
        model = criteria.loadModel();
        predictor = model.newPredictor();
    }

    public static float[] embed(String text) throws TranslateException {
        if (predictor == null) {
            try { load(); }
            catch (Exception e) { throw new RuntimeException("model load failed", e); }
        }
        return l2Normalize(predictor.predict(text));
    }

    // L2 normalize so cosine(a,b) = dot(a,b) at query time. Skips zero vectors.
    public static float[] l2Normalize(float[] v) {
        double sum = 0;
        for (float x : v) sum += x * x;
        double norm = Math.sqrt(sum);
        if (norm < 1e-12) return v;
        float[] out = new float[v.length];
        for (int i = 0; i < v.length; i++) out[i] = (float) (v[i] / norm);
        return out;
    }

    // Cosine similarity. Both inputs are assumed L2-normalized (see embed()).
    public static double cosine(float[] a, float[] b) {
        double dot = 0;
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) dot += a[i] * b[i];
        return dot;
    }

    public static void main(String[] args) throws Exception {
        String text = args.length > 0 ? String.join(" ", args) : "kubernetes networking";

        long t0 = System.currentTimeMillis();
        load();
        long tLoad = System.currentTimeMillis() - t0;

        long t1 = System.currentTimeMillis();
        float[] vec = embed(text);
        long tEmbed = System.currentTimeMillis() - t1;

        System.out.printf("model loaded in %d ms%n", tLoad);
        System.out.printf("embedded \"%s\" in %d ms%n", text, tEmbed);
        System.out.printf("dim=%d, first 5 values:", vec.length);
        for (int i = 0; i < Math.min(5, vec.length); i++) System.out.printf(" %.4f", vec[i]);
        System.out.println();

        // Quick sanity check: similar phrase should be closer than dissimilar one.
        float[] near = embed("k8s pod-to-pod networking");
        float[] far  = embed("how to bake bread");
        System.out.printf("cos(near) = %.4f%n", cosine(vec, near));
        System.out.printf("cos(far)  = %.4f%n", cosine(vec, far));
    }
}

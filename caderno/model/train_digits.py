# Treina o reconhecedor de dígitos do Caderno (CNN pequena, MNIST + 7 cortado e 1 com chapéu).
# Uso: python train_digits.py mnist.npz 12  -> gera digits.bin (pesos float32, ordem das camadas)

import numpy as np, tensorflow as tf, json, sys
rng = np.random.default_rng(0)
d = np.load(sys.argv[1])
xtr, ytr, xte, yte = d['x_train'], d['y_train'], d['x_test'], d['y_test']

def line(img, x0, y0, x1, y1, w=1.2, v=255):
    # desenha linha anti-serrilhada (distância ao segmento)
    yy, xx = np.mgrid[0:28, 0:28]
    dx, dy = x1 - x0, y1 - y0
    l2 = dx * dx + dy * dy + 1e-6
    t = np.clip(((xx - x0) * dx + (yy - y0) * dy) / l2, 0, 1)
    dist = np.hypot(xx - (x0 + t * dx), yy - (y0 + t * dy))
    a = np.clip(w + 0.5 - dist, 0, 1) * v
    np.maximum(img, a.astype(img.dtype), out=img)

def bbox(img):
    ys, xs = np.nonzero(img > 60)
    return xs.min(), ys.min(), xs.max(), ys.max()

def brazil(x, y):
    # 7 cortado e 1 com chapéu (estilo brasileiro/europeu) em parte das amostras
    x = x.copy()
    for i in np.nonzero(y == 7)[0]:
        if rng.random() < 0.5:
            x0, y0, x1, y1 = bbox(x[i]); h = y1 - y0; cy = y0 + h * rng.uniform(0.45, 0.6)
            cx = (x0 + x1) / 2 + rng.uniform(-1, 1); half = (x1 - x0) * rng.uniform(0.25, 0.4)
            line(x[i], cx - half, cy + rng.uniform(-1, 1), cx + half, cy + rng.uniform(-1, 1), w=rng.uniform(0.9, 1.5))
    for i in np.nonzero(y == 1)[0]:
        if rng.random() < 0.55:
            x0, y0, x1, y1 = bbox(x[i]);
            # topo do traço: ponto mais alto
            ys, xs = np.nonzero(x[i][y0:y0 + 3] > 60); tx = xs.mean() if len(xs) else (x0 + x1) / 2
            L = (y1 - y0) * rng.uniform(0.18, 0.33); ang = rng.uniform(38, 70) * np.pi / 180
            line(x[i], tx, y0, tx - L * np.cos(ang), y0 + L * np.sin(ang), w=rng.uniform(0.9, 1.5))
            if rng.random() < 0.2:  # base
                line(x[i], tx - 3, y1, tx + 3, y1, w=1)
    return x

xtr2 = brazil(xtr, ytr)
X = np.concatenate([xtr, xtr2[(ytr == 7) | (ytr == 1)]])[..., None].astype('float32') / 255
Y = np.concatenate([ytr, ytr[(ytr == 7) | (ytr == 1)]])
Xte = xte[..., None].astype('float32') / 255

L = tf.keras.layers
aug = tf.keras.Sequential([
    L.RandomRotation(0.04, fill_mode='constant'),
    L.RandomShear(x_factor=0.3, y_factor=0.0, fill_mode='constant') if hasattr(L, 'RandomShear') else L.Identity(),
    L.RandomZoom((-0.15, 0.1), fill_mode='constant'),
    L.RandomTranslation(0.08, 0.08, fill_mode='constant'),
])
def thick(x):
    # espessura variável: dilata ou afina aleatoriamente
    r = tf.random.uniform([tf.shape(x)[0], 1, 1, 1])
    dil = tf.nn.max_pool2d(x, 2, 1, 'SAME')
    ero = -tf.nn.max_pool2d(-x, 2, 1, 'SAME')
    return tf.where(r < 0.25, dil, tf.where(r > 0.85, tf.maximum(ero, x * 0.6), x))

inp = L.Input((28, 28, 1))
h = L.Conv2D(16, 3, activation='relu')(inp)
h = L.Conv2D(16, 3, activation='relu')(h)
h = L.MaxPooling2D()(h)
h = L.Conv2D(32, 3, activation='relu')(h)
h = L.Conv2D(32, 3, activation='relu')(h)
h = L.MaxPooling2D()(h)
h = L.Flatten()(h)
emb = L.Dense(64, activation='relu', name='emb')(h)
h = L.Dropout(0.3)(emb)
out = L.Dense(10, activation='softmax')(h)
model = tf.keras.Model(inp, out)
model.compile('adam', 'sparse_categorical_crossentropy', metrics=['accuracy'])

ds = tf.data.Dataset.from_tensor_slices((X, Y)).shuffle(80000, seed=1).batch(128)
ds = ds.map(lambda a, b: (thick(aug(a, training=True)), b), num_parallel_calls=4).prefetch(4)
model.fit(ds, epochs=int(sys.argv[2]) if len(sys.argv) > 2 else 8, validation_data=(Xte, yte), verbose=2)
print('test', model.evaluate(Xte, yte, verbose=0))

# exporta pesos (float32) na ordem das camadas
ws = []
meta = []
for layer in model.layers:
    for w in layer.get_weights():
        meta.append({'layer': layer.name, 'shape': list(w.shape)})
        ws.append(w.astype('<f4').ravel())
np.concatenate(ws).tofile('digits.bin')
json.dump(meta, open('digits.json', 'w'))
model.save('digits.keras')
np.save('probe.npy', Xte[:20]); np.save('probe_out.npy', model.predict(Xte[:20], verbose=0))

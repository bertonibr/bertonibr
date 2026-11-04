"""Unidade de refrigeracao Fronius CU 800i / 1100i / 1200i / 1400i (linha TPS/i) - modelo 3D STEP.

Base: desenhos do manual 42,0426,0115,PB (vistas dianteira, traseira e isometricas), fotos do
produto e da unidade real, e dimensoes externas 710 x 300 x 230 mm (C x L x A, sem os suportes
de fixacao do topo). Posicoes medidas nos desenhos por escala (~0,27 mm/px na vista ampliada);
cores amostradas das fotos. Detalhes aproximados - envelope e layout fieis ao produto.

Coordenadas: origem no centro da base; +X = lado dianteiro (tampa do reservatorio),
+Y = lado direito visto de frente (grade de saida de ar), +Z = para cima.
Rodar: python3 gerar_step.py -> gera fronius_cu.step
"""
import os

import cadquery as cq

# ---------------- dimensoes principais (mm) ----------------
L, W, H = 710.0, 300.0, 230.0
XF = L / 2                       # face dianteira em +XF, traseira em -XF
CAP = 40.0                       # profundidade das molduras vermelhas
BEV = 20.0                       # profundidade das facetas perimetrais da moldura
INSET = 4.0                      # recuo das laterais cinza em relacao as molduras
TOP_T = 8.0                      # espessura da tampa superior vermelha
BODY_Z0, BODY_Z1 = 9.0, H - TOP_T
Z_VINCO, RECUO = 150.0, 25.0     # abaixo de Z_VINCO a face inclina para tras (RECUO mm no piso)

# contorno externo da moldura (Y, Z): chanfros nos 4 cantos, como na vista frontal do manual
CAP_PROFILE = [(-150, 9), (-124, 0), (124, 0), (150, 9), (150, 222), (123, 230), (-123, 230), (-150, 222)]
# face plana da moldura (contorno interno da vista frontal); mesma quantidade de vertices p/ o loft
FACE_PROFILE = [(-121, 24), (-101, 19), (101, 19), (121, 24), (121, 206), (116, 211), (-116, 211), (-121, 206)]
# tampa superior: mesmo chanfro das molduras + abas que cobrem o topo das laterais
TOP_PROFILE = [(-150, 216), (-146, 216), (-146, 222), (146, 222), (146, 216), (150, 216), (150, 222),
               (123, 230), (-123, 230), (-150, 222)]


def safe_fillet(obj, edges, r):
    """Arredonda se o OCC conseguir; senao devolve a peca sem o arredondamento."""
    try:
        sel = obj.edges(edges) if isinstance(edges, str) else edges(obj)
        res = sel.fillet(r)
        if res.val().isValid():
            return res
    except Exception:
        pass
    return obj


def yz(x):
    """Plano YZ em X=x (normal +X): x local = Y, y local = Z."""
    return cq.Workplane("YZ").workplane(offset=x)


def polygon_wire(pts, x):
    return cq.Wire.makePolygon([cq.Vector(x, py, pz) for py, pz in pts], close=True)


def face_x(z):
    """X da superficie externa da moldura dianteira na altura z (parte vertical + plano inclinado)."""
    return XF if z >= Z_VINCO else XF - RECUO * (Z_VINCO - z) / Z_VINCO


# ---------------- molduras (dianteira; a traseira e o espelho) ----------------
def cap_shape():
    blk = yz(XF - CAP).polyline(CAP_PROFILE).close().extrude(CAP - BEV)
    bev = cq.Solid.makeLoft([polygon_wire(CAP_PROFILE, XF - BEV), polygon_wire(FACE_PROFILE, XF)], ruled=True)
    slope = (cq.Workplane("XZ").polyline([(XF, Z_VINCO), (XF + 60, Z_VINCO), (XF + 60, -30),
                                          (XF - RECUO * (Z_VINCO + 30) / Z_VINCO, -30)]).close()
             .extrude(W, both=True))
    feet = yz(XF - CAP - 10).polyline([(-96, -1), (-83, 7), (83, 7), (96, -1)]).close().extrude(CAP + 20)
    return blk.union(bev).cut(slope).cut(feet)          # pes nos cantos: centro da base 7 mm acima do piso


def skin(shape, depth, sign):
    """Camada da moldura ate `depth` mm abaixo da superficie externa (medido ao longo de X)."""
    return shape.cut(shape.translate((-sign * depth, 0, 0)))


def outer_layer(shape, t, sign):
    """Camada de espessura t logo acima da superficie externa (para etiquetas sobre faces inclinadas)."""
    return shape.translate((sign * t, 0, 0)).cut(shape)


def holes(pts, sign, base, d=6.0, depth=6.0):
    prism = yz(-25.0 + sign * (XF - 20.0)).pushPoints(pts).circle(d / 2).extrude(50.0)
    return prism.intersect(skin(base, depth, sign))


base_f = cap_shape()
base_r = base_f.mirror("YZ")
front, rear = base_f, base_r

# ---------------- frente ----------------
# (1)(2) rebaixo retangular com paredes inclinadas; tampas cegas no fundo
POCKET_X = XF - 26.0


def rect_wire(y0, y1, z0, z1, x):
    return polygon_wire([(y0, z0), (y1, z0), (y1, z1), (y0, z1)], x)


pocket = cq.Solid.makeLoft([rect_wire(-118.0, -30.0, 156.0, 207.0, XF + 0.5),
                            rect_wire(-112.0, -36.0, 170.0, 203.0, POCKET_X)], ruled=True)
front = front.cut(pocket)
covers = yz(POCKET_X).pushPoints([(-86.8, 188.7), (-52.5, 188.7)]).circle(14.0).extrude(2.0)
covers = safe_fillet(covers, lambda o: o.faces(">X").edges(), 0.8)

# (3) tampa do reservatorio: borracha em formato de escudo, alca no topo, oval do logo e frisos
neck = yz(XF - 10.0).center(0, 198.0).circle(20.0).extrude(13.0).cut(base_f)
shield = (yz(XF + 3.0).center(0, 196.0).circle(28.0).extrude(18.0)
          .union(yz(XF + 3.0).center(0, 158.0).circle(22.0).extrude(18.0))
          .union(yz(XF + 3.0).polyline([(-28, 196), (28, 196), (22, 158), (-22, 158)]).close().extrude(18.0)))
shield = safe_fillet(shield, lambda o: o.faces(">X").edges(), 4.0)
shield = shield.union(yz(XF + 21.0).center(0, 204.0).ellipse(17.0, 6.0).extrude(0.8))          # oval do logo
shield = shield.cut(yz(XF + 20.2).pushPoints([(0, 172.0), (0, 162.0), (0, 152.0)]).rect(22.0, 1.6).extrude(1.0))
strap = yz(XF + 4.0).center(0, 229.0).circle(9.0).circle(5.0).extrude(6.0)                   # alca
filler = neck.union(shield).union(strap)

# (4) etiqueta em rebaixo raso (lado direito inclinado), com quadro preto de avisos no canto
LBL = [(29.5, 168.0), (99.0, 168.0), (117.0, 207.0), (29.5, 207.0)]
front = front.cut(yz(XF - 1.2).polyline(LBL).close().offset2D(1.5).extrude(5.0))
label = yz(XF - 1.2).polyline(LBL).close().extrude(0.3)
label_blk = yz(XF - 0.9).center(84.0, 178.0).rect(24.0, 18.0).extrude(0.1)

# (5) indicador de nivel acompanhando a face inclinada, com marcas max/min
level_cut = yz(XF - 45.0).center(0, 85.0).slot2D(90.0, 12.0, 90).extrude(60.0)
front = front.cut(level_cut.intersect(skin(base_f, 3.0, 1)))
level = (yz(XF - 45.0).center(0, 85.0).slot2D(89.0, 11.0, 90).extrude(60.0)
         .intersect(skin(base_f, 3.0, 1).cut(skin(base_f, 0.5, 1))))
marks = (yz(XF - 45.0).center(16.0, 122.0).rect(10.0, 10.0).extrude(60.0)
         .union(yz(XF - 45.0).center(16.0, 58.0).rect(10.0, 30.0).extrude(60.0))
         .intersect(outer_layer(base_f, 0.3, 1)))

front = front.cut(holes([(-85.8, 29.5), (84.7, 29.5), (-110.0, 9.0), (109.0, 9.0)], 1, base_f))

# ---------------- tras: (6)(7) engates, (8) placa, (10) conexao, grade de entrada de ar ----------
# posicoes da vista traseira do manual convertidas para Y global (Y = -x da vista traseira)


def coupling(y, z):
    ring = yz(-XF - 4.0).center(y, z).circle(13.0).extrude(4.0)                    # anel colorido
    metal = yz(-XF - 14.0).center(y, z).polygon(6, 21.0).extrude(10.0)             # sextavado
    metal = metal.union(yz(-XF - 28.0).center(y, z).circle(7.0).extrude(14.0))     # niple
    metal = metal.cut(yz(-XF - 22.0).center(y, z).circle(7.5).circle(6.0).extrude(2.0))
    metal = metal.cut(yz(-XF - 28.0).center(y, z).circle(3.5).extrude(8.0))
    return ring, metal


ring_blue, brass_blue = coupling(101.0, 189.0)      # (6) avanco do refrigerante (azul)
ring_red, brass_red = coupling(70.4, 189.0)         # (7) retorno do refrigerante (vermelho)

gland = (yz(-XF - 3.0).center(-82.3, 189.0).circle(12.5).extrude(3.0)
         .union(yz(-XF - 7.0).center(-82.3, 189.0).circle(7.5).extrude(4.0)))
gland = gland.cut(yz(-XF - 7.0).center(-82.3, 189.0).circle(2.5).extrude(3.0))

plate = yz(-XF - 0.4).center(6.0, 178.5).rect(74.0, 53.0).extrude(0.4)

grill = safe_fillet(yz(-XF - 15.0).center(0, 85.0).rect(230.0, 94.0).extrude(60.0), "|X", 6.0)
grill = grill.intersect(skin(base_r, 18.0, -1))
rear = rear.cut(grill)
slats = None
for zs in (116.0, 94.0, 72.0, 50.0):
    s = (cq.Workplane("XY").box(16.0, 240.0, 2.5)        # aparada pela grade -> encosta nas paredes
         .rotate((0, 0, 0), (0, 1, 0), -35)            # aleta inclinada, borda externa para baixo
         .translate((-face_x(zs) + 9.0, 0, zs)))
    slats = s if slats is None else slats.union(s)
rear = rear.union(slats.intersect(grill))
rear = rear.cut(holes([(112.0, 29.5), (0.0, 29.5), (-109.7, 29.5), (110.7, 9.0), (-109.7, 9.0)], -1, base_r))

# ---------------- carcaca: laterais cinza + tampa superior vermelha ----------------
body = (cq.Workplane("XY").box(L - 2 * CAP, W - 2 * INSET, BODY_Z1 - BODY_Z0, centered=(True, True, False))
        .translate((0, 0, BODY_Z0)))
body = safe_fillet(body, "|X and <Z", 3.0)

# grade de saida de ar (2) no lado direito: furos oblongos em quinconcio
vent_pts = []
for i in range(13):
    z = 42.0 + i * 13.0
    off, ncol = (0.0, 12) if i % 2 == 0 else (14.0, 11)
    x0 = -34.0 - 11 * 28.0 / 2 + off
    vent_pts += [(x0 + j * 28.0, z) for j in range(ncol)]
side_r = cq.Workplane(cq.Plane(origin=(0, W / 2 - INSET + 1.0, 0), xDir=(1, 0, 0), normal=(0, -1, 0)))
body = body.cut(side_r.pushPoints(vent_pts).slot2D(24.0, 5.0).extrude(4.0))


def screws(wp, pts, d=7.0, h=2.0):
    head = wp.pushPoints(pts).circle(d / 2).extrude(h)
    return head.cut(wp.workplane(offset=h - 1.0).pushPoints(pts).polygon(6, d * 0.42).extrude(2.0))


side_pts = [(-200.0, 18.0), (0.0, 18.0), (200.0, 18.0)]
scr = screws(cq.Workplane(cq.Plane(origin=(0, -(W / 2 - INSET), 0), xDir=(1, 0, 0), normal=(0, -1, 0))), side_pts)
scr = scr.union(screws(cq.Workplane(cq.Plane(origin=(0, W / 2 - INSET, 0), xDir=(-1, 0, 0), normal=(0, 1, 0))),
                       side_pts))

top = yz(-(XF - CAP)).polyline(TOP_PROFILE).close().extrude(L - 2 * CAP)
top = top.cut(cq.Workplane("XY").workplane(offset=H - 3.0).pushPoints([(143.0, -4.0), (122.0, 7.0)])
              .circle(2.0).extrude(4.0))

# ---------------- topo: suportes de fixacao e conector de sistema ----------------
lugs = None
for xc in (XF - CAP + 13.0, -(XF - CAP + 13.0)):
    for yc in (-110.0, 110.0):
        lug = cq.Workplane("XY").box(6.0, 24.0, 20.0, centered=(True, True, False)).translate((xc, yc, H))
        lug = safe_fillet(lug, "|X and >Z", 6.0)
        lug = lug.cut(cq.Workplane("YZ").workplane(offset=xc - 5.0).center(yc, H + 10.0).circle(3.25).extrude(10.0))
        lugs = lug if lugs is None else lugs.union(lug)

CONN = (-255.0, -81.0)
conn = cq.Workplane("XY").box(100.0, 34.0, 17.0, centered=(True, True, False)).translate((CONN[0], CONN[1], H))
conn = safe_fillet(conn, "|Z", 3.0)
conn = conn.cut(cq.Workplane("XY").workplane(offset=H + 8.0).center(*CONN).rect(84.0, 22.0).extrude(10.0))
pins = (cq.Workplane("XY").workplane(offset=H + 8.0)
        .pushPoints([(CONN[0] - 31.5 + 7.0 * i, CONN[1] + dy) for i in range(10) for dy in (-5.0, 5.0)])
        .circle(0.8).extrude(6.0))


# ---------------- montagem com cores (amostradas das fotos do produto) ----------------
def rgb(r, g, b):
    return cq.Color(r / 255, g / 255, b / 255)


VERMELHO = rgb(215, 23, 26)
CINZA = rgb(58, 67, 70)
PRETO = rgb(30, 32, 33)

asm = cq.Assembly(name="fronius_CU_800i_1400i")
asm.add(body, name="laterais", color=CINZA)
asm.add(top, name="tampa_superior", color=VERMELHO)
asm.add(front, name="moldura_dianteira", color=VERMELHO)
asm.add(rear, name="moldura_traseira", color=VERMELHO)
asm.add(lugs, name="suportes_fixacao", color=VERMELHO)
asm.add(filler, name="tampa_reservatorio", color=PRETO)
asm.add(covers, name="tampas_cegas", color=rgb(48, 40, 42))
asm.add(level, name="indicador_nivel", color=rgb(210, 212, 214))
asm.add(marks, name="marcas_min_max", color=rgb(235, 235, 235))
asm.add(label, name="etiqueta_frontal", color=rgb(225, 227, 225))
asm.add(label_blk, name="etiqueta_avisos", color=rgb(35, 35, 35))
asm.add(plate, name="placa_identificacao", color=rgb(205, 207, 207))
asm.add(ring_blue, name="anel_avanco_azul", color=rgb(40, 80, 170))
asm.add(ring_red, name="anel_retorno_vermelho", color=rgb(170, 15, 20))
asm.add(brass_blue.union(brass_red), name="engates_refrigerante", color=rgb(185, 145, 75))
asm.add(gland, name="conexao_traseira_10", color=rgb(45, 45, 47))
asm.add(scr, name="parafusos", color=rgb(40, 42, 44))
asm.add(conn, name="conector_sistema", color=rgb(80, 82, 84))
asm.add(pins, name="conector_pinos", color=rgb(212, 172, 72))

if __name__ == "__main__":
    out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "fronius_cu.step")
    asm.export(out)
    print("STEP salvo:", out)

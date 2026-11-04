"""Modelo STEP do modulo cinza escuro (sensor/trocador com display, botao e anel de trava).

Dimensoes estimadas a partir das fotos com trena (escala ~123 px/cm, correcao de perspectiva).
Eixo Z = eixo da ferramenta; Z=0 na face inferior (interface com o suporte de aluminio).
Rodar: python3 gerar_step.py  -> gera sensor_colisao.step
"""
import math
import cadquery as cq

# ---------------- parametros (mm) ----------------
R_BODY = 35.0          # corpo principal  O70
Z_BODY0, Z_BODY1 = 25.0, 70.0
R_NECK = 25.0          # pescoco inferior O50
Z_NECK1 = 6.0
R_LIP = 35.5           # aba fina acima do pescoco
Z_LIP1 = 7.5
R_RING = 37.5          # anel preto recartilhado O75
Z_RING0, Z_RING1 = 7.5, 25.0
N_FLUTES, FLUTE_W, FLUTE_D = 16, 7.0, 1.6
TOP = [(70.0, 71.5, 32.5), (71.5, 75.5, 32.0), (75.5, 78.0, 30.0)]  # (z0, z1, raio)

# face do display (-Y)
WIN_W, WIN_H, WIN_ZC = 35.0, 17.0, 47.0
PLATE_W, PLATE_H = 50.0, 32.0
# face lateral (+X): botao e conector M8
X_FLAT = 30.0
BTN_ZC, BTN_R_OUT, BTN_R_IN = 40.0, 9.5, 6.5
M8_ZC = 55.5


def cyl(r, z0, z1):
    return cq.Workplane("XY").workplane(offset=z0).circle(r).extrude(z1 - z0)


# ---------------- corpo cinza ----------------
body = cyl(R_BODY, Z_BODY0, Z_BODY1).edges(">Z or <Z").chamfer(0.8)
for z0, z1, r in TOP:
    body = body.union(cyl(r, z0, z1))
body = body.union(cyl(R_LIP, Z_NECK1, Z_LIP1))
neck = cyl(R_NECK, 0, Z_NECK1).edges("<Z").chamfer(0.5)  # pescoco anodizado mais escuro
body = body.union(cyl(R_NECK + 3, Z_LIP1, Z_BODY0))  # cubo interno sob o anel

# linha de particao do corpo
groove = cyl(R_BODY + 1, 63.0, 63.4).cut(cyl(R_BODY - 0.4, 63.0, 63.4))
body = body.cut(groove)

# faceta lateral +X com chanfro superior (alojamento do botao/conector)
# faceta plana ate Z62 (acima disso o chanfro); nao invade os degraus do topo
flat = (cq.Workplane("XY").box(20, 80, 62.0 - (Z_BODY0 + 1.5), centered=(False, True, False))
        .translate((X_FLAT, 0, Z_BODY0 + 1.5)))
body = body.cut(flat)
# chanfro: plano inclinado do topo da faceta ate o ombro superior
wedge = (cq.Workplane("XZ").polyline([(X_FLAT, 62.0), (R_BODY + 2, 62.0), (R_BODY + 2, 68.5),
                                      (R_BODY - 1.0, 68.5)]).close()
         .extrude(40, both=True))
body = body.cut(wedge)

# moldura do display: ranhura do contorno da tampa + janela rebaixada
plate_outline = (cq.Workplane("XZ").workplane(offset=0)
                 .center(0, WIN_ZC).rect(PLATE_W, PLATE_H).extrude(-50)
                 .edges("|Y").fillet(3.5))
plate_inner = (cq.Workplane("XZ").center(0, WIN_ZC).rect(PLATE_W - 0.8, PLATE_H - 0.8)
               .extrude(-50).edges("|Y").fillet(3.1))
ring_cut = plate_outline.cut(plate_inner).intersect(
    cyl(R_BODY + 1, 0, 100).cut(cyl(R_BODY - 0.4, 0, 100)))
body = body.cut(ring_cut.mirror("XZ"))

# janela rebaixada ate y=-31 (4 mm de profundidade)
window = (cq.Workplane("XZ").center(0, WIN_ZC).rect(WIN_W, WIN_H).extrude(15)
          .edges("|Y").fillet(2.5).translate((0, -31.0, 0)))
body = body.cut(window)
# chanfro externo da janela (bisel)
bevel = (cq.Workplane("XZ").workplane(offset=31.0).center(0, WIN_ZC)
         .rect(WIN_W, WIN_H).workplane(offset=5).rect(WIN_W + 7, WIN_H + 7).loft())
body = body.cut(bevel)

# furos roscados da tampa e conector
screw_pos = [(sx * (PLATE_W / 2 - 4), WIN_ZC + sz * (PLATE_H / 2 - 4)) for sx in (-1, 1) for sz in (-1, 1)]
for sx, sz in screw_pos:
    body = body.cut(cq.Workplane("XZ").center(sx, sz).circle(1.3).extrude(8)
                    .translate((0, -22.0, 0)))

# alojamentos do botao e do M8 na faceta
body = body.cut(cq.Workplane("YZ").workplane(offset=X_FLAT - 6).center(0, BTN_ZC).circle(8).extrude(10))
body = body.cut(cq.Workplane("YZ").workplane(offset=X_FLAT - 6).center(0, M8_ZC).circle(4.0).extrude(10))

# ---------------- anel preto recartilhado ----------------
ring = cyl(R_RING, Z_RING0, Z_RING1).cut(cyl(R_NECK + 3.05, Z_RING0 - 1, Z_RING1 + 1))
ring = ring.faces(">Z or <Z").edges(cq.selectors.RadiusNthSelector(-1)).chamfer(1.0)
for i in range(N_FLUTES):
    a = 360.0 / N_FLUTES * i
    f = (cq.Workplane("XY").box(FLUTE_D * 2, FLUTE_W, Z_RING1 - Z_RING0 - 5.0)
         .translate((R_RING, 0, (Z_RING0 + Z_RING1) / 2 - 0.5))
         .edges("|X").fillet(1.2)
         .rotate((0, 0, 0), (0, 0, 1), a))
    ring = ring.cut(f)

# ---------------- display (vidro escuro) ----------------
screen = (cq.Workplane("XZ").center(0, WIN_ZC).rect(WIN_W - 0.2, WIN_H - 0.2).extrude(1.0)
          .edges("|Y").fillet(2.4).translate((0, -31.0, 0)))  # apoiado no fundo da janela (y -31..-32)

# ---------------- parafusos da tampa (cabeca abaulada sextavado interno) ----------------
screws = None
for sx, sz in screw_pos:
    y_surf = -math.sqrt(R_BODY ** 2 - sx ** 2)
    h = (cq.Workplane("XZ").center(sx, sz).circle(2.75).extrude(1.6)
         .faces("<Y").fillet(0.7)
         .faces("<Y").workplane().polygon(6, 2.3).cutBlind(-1.0)
         .translate((0, y_surf + 0.3, 0)))
    screws = h if screws is None else screws.union(h)

# ---------------- botao inox ----------------
button = (cq.Workplane("YZ").workplane(offset=X_FLAT - 2).center(0, BTN_ZC).circle(BTN_R_OUT).extrude(3.5)
          .faces(">X").edges().chamfer(1.2)
          .faces(">X").workplane().circle(BTN_R_IN).cutBlind(-0.6))
button = button.union(cq.Workplane("YZ").workplane(offset=X_FLAT - 6).center(0, BTN_ZC).circle(7.9).extrude(4))
cap = cq.Workplane("YZ").workplane(offset=X_FLAT + 0.9).center(0, BTN_ZC).circle(BTN_R_IN - 0.3).extrude(2.0) \
    .faces(">X").edges().fillet(0.4)
button = button.union(cap)

# ---------------- conector M8 8 pinos (macho) ----------------
m8 = (cq.Workplane("YZ").workplane(offset=X_FLAT).center(0, M8_ZC).polygon(6, 11.5).extrude(3.0)
      .faces(">X").edges().chamfer(0.5))
m8 = m8.union(cq.Workplane("YZ").workplane(offset=X_FLAT + 3.0).center(0, M8_ZC).circle(4.0).extrude(7.0))
m8 = m8.union(cq.Workplane("YZ").workplane(offset=X_FLAT - 6).center(0, M8_ZC).circle(3.95).extrude(6))
m8 = m8.faces(">X").workplane().circle(3.0).cutBlind(-4.0)
pins = cq.Workplane("YZ").workplane(offset=X_FLAT + 6.0).center(0, M8_ZC)
pin_pts = [(0, 0)] + [(1.9 * math.cos(math.radians(a)), 1.9 * math.sin(math.radians(a)))
                      for a in range(0, 360, 360 // 7)][:7]
pins = pins.pushPoints(pin_pts).circle(0.4).extrude(3.5)
insert = cq.Workplane("YZ").workplane(offset=X_FLAT + 6.0).center(0, M8_ZC).circle(3.0).extrude(0.5)

# ---------------- montagem ----------------
# cores amostradas das fotos (mediana de pixels em areas iluminadas, RGB 0-255)
# e conferidas com a imagem de catalogo do produto (luz de estudio, corpo mais claro)
def rgb(r, g, b):
    return cq.Color(r / 255, g / 255, b / 255)


COR_CORPO = rgb(146, 144, 140)    # cinza titanio anodizado fosco, levemente quente
COR_PESCOCO = rgb(78, 79, 88)      # anodizado mais escuro, reflexo azulado
COR_ANEL = rgb(20, 20, 22)         # preto anodizado brilhante
COR_DISPLAY = rgb(10, 10, 14)      # vidro do OLED desligado
COR_INOX = rgb(196, 195, 193)      # botao e parafusos inox escovado
COR_NIQUEL = rgb(172, 169, 165)    # corpo do conector M8 niquelado
COR_PINOS = rgb(212, 172, 72)      # pinos dourados
COR_ISOLANTE = rgb(15, 15, 15)     # isolante preto do M8

asm = cq.Assembly(name="sensor_colisao")
asm.add(body, name="corpo", color=COR_CORPO)
asm.add(neck, name="pescoco", color=COR_PESCOCO)
asm.add(ring, name="anel_trava", color=COR_ANEL)
asm.add(screen, name="display", color=COR_DISPLAY)
asm.add(screws, name="parafusos_tampa", color=COR_INOX)
asm.add(button, name="botao", color=COR_INOX)
asm.add(m8, name="conector_M8", color=COR_NIQUEL)
asm.add(insert, name="m8_isolante", color=COR_ISOLANTE)
asm.add(pins, name="m8_pinos", color=COR_PINOS)

if __name__ == "__main__":
    import os
    out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "sensor_colisao.step")
    asm.save(out)  # exporta STEP AP214 com cores
    print("STEP salvo:", out)

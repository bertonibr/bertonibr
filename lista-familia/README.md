# Lista Família — listas compartilhadas (PWA)

App web instalável (iPhone pelo Safari, Android/PC pelo Chrome) para listas de
mercado, de viagem e gerais, **compartilhadas** entre os membros da família.

## Recursos
- Listas com tipo: 🛒 Mercado, 🧳 Viagem, 📝 Geral; renomear, duplicar, excluir
- Itens digitados pelo teclado (Enter adiciona e o cursor continua no campo)
- Tocar no item marca/desmarca; marcados vão para "No carrinho"/"Marcados"
- Sugestões de itens já usados em listas do mesmo tipo; item repetido não duplica
- Editar/excluir item (com **Desfazer**), "Desmarcar tudo" (bom para lista de viagem
  reutilizável), "Apagar marcados", compartilhar como texto (WhatsApp)
- **Tempo real**: o que um marca, o outro vê na hora
- **Offline**: salvo no aparelho; envia quando a internet volta (bolinha: verde =
  sincronizado, amarela = enviando, cinza = sem internet)

## Como funciona
- Dados no **Cloud Firestore** (Firebase, plano gratuito) com cache offline (IndexedDB).
- Login: Google OAuth por redirecionamento (mesmo Client ID do Caderno) → Firebase Auth.
- Acesso restrito aos e-mails listados em `firestore.rules`.
- Configuração em `config.js` (`firebase`: objeto firebaseConfig do Console).

## Testar localmente (emulador do Firebase)
```bash
npx firebase-tools emulators:start --only auth,firestore --project demo-lista-familia
cd lista-familia && python3 -m http.server 8000
# abra http://localhost:8000/?emu=1&email=voce@teste.com
```

Bibliotecas em `lib/`: Firebase JS SDK 12.6.0 (compat, Apache-2.0).

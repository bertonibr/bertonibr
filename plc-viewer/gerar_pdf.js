// Gera PDF com todas as rotinas ladder: node gerar_pdf.js PROJETO.html SAIDA.pdf "Título"
const { chromium } = require(process.env.PW || "playwright");
(async () => {
  const [src, out, title] = process.argv.slice(2);
  const b = await chromium.launch();
  const p = await b.newPage({ viewport: { width: 1060, height: 800 } });
  const errs = []; p.on('pageerror', e => errs.push(e.message));
  await p.emulateMedia({ media: 'print', colorScheme: 'light' });
  await p.goto('file://' + src + '#print');
  await p.waitForSelector('body[data-ready="1"]', { timeout: 600000 });
  await p.pdf({ path: out, format: 'A4', landscape: true, printBackground: true,
    margin: { top: '12mm', bottom: '14mm', left: '10mm', right: '10mm' },
    displayHeaderFooter: true,
    headerTemplate: `<div style="font-size:8px;width:100%;padding:0 10mm;color:#888">${title}</div>`,
    footerTemplate: '<div style="font-size:8px;width:100%;text-align:right;padding:0 10mm;color:#888"><span class="pageNumber"></span> / <span class="totalPages"></span></div>' });
  console.log('errors:', errs);
  await b.close();
})();

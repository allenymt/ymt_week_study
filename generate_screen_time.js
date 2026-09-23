const { Document, Packer, Paragraph, TextRun, Table, TableRow, TableCell,
        AlignmentType, BorderStyle, WidthType, VerticalAlign, ShadingType } = require('docx');
const fs = require('fs');

const W = 10206; // usable width DXA (A4 - 1.25cm margins each side)
const B  = { style: BorderStyle.SINGLE, size: 4, color: "000000" };
const TB = { style: BorderStyle.SINGLE, size: 2, color: "000000" };
const tcb = { top: TB, bottom: TB, left: TB, right: TB };
const ROW_H = 420; // fixed day-row height (twips) so the table always fits one page
const HBG = "D9E1F2", SBG = "E2EFDA", GBG = "F2F2F2";
const SZ = 17, HSZ = 19; // 8.5pt body, 9.5pt header

function mkCell(text, width, { bold=false, center=true, bg=null, colSpan, sz=SZ, italic=false } = {}) {
  const lines = Array.isArray(text) ? text : [text];
  return new TableCell({
    borders: tcb,
    width: { size: width, type: WidthType.DXA },
    verticalAlign: VerticalAlign.CENTER,
    ...(bg && { shading: { fill: bg, type: ShadingType.CLEAR } }),
    ...(colSpan && { columnSpan: colSpan }),
    children: lines.map(line => new Paragraph({
      alignment: center ? AlignmentType.CENTER : AlignmentType.LEFT,
      spacing: { before: 20, after: 20 },
      children: [new TextRun({ text: line, bold, italics: italic, size: sz, font: "宋体" })]
    }))
  });
}
const hc = (t, w, o={}) => mkCell(t, w, { bold:true, bg:HBG, sz:HSZ, ...o });
const dc = (t, w, o={}) => mkCell(t, w, o);
const gc = (t, w, o={}) => mkCell(t, w, { bg:GBG, bold:true, ...o });

function secTitle(n, title) {
  return new Paragraph({
    spacing: { before: 40, after: 20 },
    children: [new TextRun({ text: `${n}、${title}`, bold:true, size:20, font:"宋体" })]
  });
}
function note(text) {
  return new Paragraph({
    spacing: { before: 0, after: 0 },
    indent: { left: 360 },
    children: [new TextRun({ text, size:15, font:"宋体" })]
  });
}
function infoLine(...items) {
  return new Paragraph({
    spacing: { before: 20, after: 20 },
    children: items.map(t => new TextRun({ text: t, size:19, font:"宋体" }))
  });
}
// ---------- 标题 ----------
const title = new Paragraph({
  alignment: AlignmentType.CENTER,
  spacing: { after: 80 },
  children: [new TextRun({ text: "每周屏幕时间记录表", bold:true, size:32, font:"宋体" })]
});
const subLine = infoLine("周次：________　　", "日期：______ 月 ______ 日 ～ ______ 月 ______ 日");
const nameLine = infoLine("孩子姓名：__________________　　", "家长签字：__________________");

// ---------- 一、每日加分记录 ----------
const DAY_ROWS = ["周一","周二","周三","周四","周五","周六","周日"];
// widths: 星期, 日期, 5项, 投诉, 当日小计  => 累计 W
const wDay = [900, 1100, 900, 900, 900, 900, 900, 900, 1266];
const dayTable = new Table({
  columnWidths: wDay,
  margins: { top: 40, bottom: 40, left: 60, right: 60 },
  rows: [
    new TableRow({ tableHeader: true, children: [
      hc("星期", wDay[0]), hc("日期", wDay[1]),
      hc("举手回答\n+10", wDay[2]), hc("字认真\n+10", wDay[3]), hc("主动英语\n+10", wDay[4]),
      hc("主动阅读\n+15", wDay[5]), hc("主动运动\n+15", wDay[6]),
      hc("投诉\n-30", wDay[7]), hc("当日小计", wDay[8]),
    ]}),
    ...DAY_ROWS.map((d, i) => new TableRow({ height: { value: ROW_H, rule: "atLeast" }, children: [
      dc(d, wDay[0]),
      dc("", wDay[1]),
      ...[2,3,4,5,6,7].map(c => dc("□", wDay[c])),
      dc("", wDay[8]),
    ]})),
    new TableRow({ children: [
      gc("合计", wDay[0], { colSpan: 8, center: true }), gc("", wDay[8], { center: true }),
    ]}),
  ]
});

// ---------- 二、考试奖励记录 ----------
const wSub = [1400, 1400, 1600, 5806];
const subRows = [
  ["英语", "85–89 +15；90 及以上 +30"],
  ["语文", "90 及以上 +30；85–89 无"],
  ["数学", "90 及以上 +30；85–89 无"],
  ["科学", "90 及以上 +30；85–89 无"],
];
const examTable = new Table({
  columnWidths: wSub,
  margins: { top: 40, bottom: 40, left: 60, right: 60 },
  rows: [
    new TableRow({ tableHeader: true, children: [
      hc("科目", wSub[0]), hc("分数", wSub[1]), hc("奖励分钟", wSub[2]), hc("备注", wSub[3], { center:false }),
    ]}),
    ...subRows.map(([k, noteTxt]) => new TableRow({ children: [
      dc(k, wSub[0]), dc("", wSub[1]), dc("", wSub[2]), dc(noteTxt, wSub[3], { center:false }),
    ]})),
    new TableRow({ children: [
      gc("合计", wSub[0], { colSpan: 2 }), gc("", wSub[2]), gc("", wSub[3]),
    ]}),
  ]
});

// ---------- 三、本周汇总 ----------
const wSum = [4200, 1900, 1900, 2206];
const sumRows = [
  ["保底（需表现解锁）", "60", "1h", "", true],
  ["一周无投诉", "60", "1h", "", true],
  ["每日加分合计", "", "", "", false],
  ["考试奖励合计", "", "", "", false],
  ["投诉扣分", "", "", "", false],
  ["本周总赚取", "", "", "", true],
  ["本周已使用", "", "", "", false],
  ["本周结余", "", "", "", true],
];
const sumTable = new Table({
  columnWidths: wSum,
  margins: { top: 40, bottom: 40, left: 60, right: 60 },
  rows: [
    new TableRow({ tableHeader: true, children: [
      hc("项目", wSum[0]), hc("分钟", wSum[1]), hc("小时", wSum[2]), hc("备注", wSum[3]),
    ]}),
    ...sumRows.map(([k, m, h, r, bold]) => new TableRow({ children: [
      dc(k, wSum[0], { center:false, bold }), dc(m, wSum[1]), dc(h, wSum[2], { bold }), dc(r, wSum[3], { center:false }),
    ]})),
  ]
});
// ---------- 四、屏幕银行 ----------
const wBank = [4400, 2900, 2906];
const bankRows = [
  ["上周银行结余", "", ""],
  ["本周结余", "", ""],
  ["新银行累计（上限 8 小时）", "", ""],
  ["超出 8 小时部分", "", "分钟"],
  ["兑换金额（每 30 分钟 2 元，每周最多 10 元）", "", "元"],
  ["存钱累计（上限 50 元）", "", "元"],
];
const bankTable = new Table({
  columnWidths: wBank,
  margins: { top: 40, bottom: 40, left: 60, right: 60 },
  rows: [
    new TableRow({ tableHeader: true, children: [
      hc("项目", wBank[0]), hc("小时 / 元", wBank[1]), hc("备注", wBank[2]),
    ]}),
    ...bankRows.map(([k, v, r]) => new TableRow({ children: [
      dc(k, wBank[0], { center:false }), dc(v, wBank[1]), dc(r, wBank[2]),
    ]})),
  ]
});

// ---------- 五、使用规则提醒 ----------
const rules = [
  "屏幕时间只能周末使用，周一到周五不开放。",
  "每次最多 1 小时，中间休息 10 分钟。",
  "内容：动画片、益智游戏、纪录片，不含短视频。",
  "超时 1 分钟，下次扣 10 分钟。",
  "钱归孩子，花前商量，不抵惩罚。",
];
const ruleTable = new Table({
  columnWidths: [W],
  margins: { top: 60, bottom: 60, left: 120, right: 120 },
  rows: rules.map((r, i) => new TableRow({ children: [ new TableCell({
    borders: tcb,
    width: { size: W, type: WidthType.DXA },
    children: [new Paragraph({
      spacing: { before: 20, after: 20 },
      children: [new TextRun({ text: `${i + 1}. ${r}`, size: 18, font: "宋体" })]
    })]
  }) ]}))
});

// ---------- 六、本周一句话总结 ----------
function blankLine(label) {
  return new Paragraph({
    spacing: { before: 30, after: 30 },
    children: [new TextRun({ text: label, size: 19, font: "宋体" }),
               new TextRun({ text: "______________________________________________", size: 19, font: "宋体" })]
  });
}
const signLine = new Paragraph({
  alignment: AlignmentType.RIGHT,
  spacing: { before: 120 },
  children: [new TextRun({ text: "孩子签名：____________　　家长签名：____________　　", size: 19, font: "宋体" })]
});

// ---------- 组装 ----------
const doc = new Document({
  styles: { default: { document: { run: { font: "宋体", size: 20 } } } },
  sections: [{
    properties: {
      page: {
        size: { width: 11906, height: 16838 },  // A4 portrait
        margin: { top: 709, bottom: 709, left: 850, right: 850 }, // 1.25cm / 1.5cm
      }
    },
    children: [
      title, subLine, nameLine,
      secTitle("一", "每日加分记录（上学日 + 周末）"),
      dayTable,
      note("说明："),
      note("举手回答：孩子自己说“有举手”，睡前记录。"),
      note("字认真：工整、无涂改、按时完成。"),
      note("主动英语：不催、自己学 15 分钟以上。"),
      note("主动阅读 / 运动：每次 30 分钟。"),
      note("投诉：仅指老师正式投诉，单周最多扣 1 小时（2 次）。"),
      secTitle("二", "考试奖励记录"),
      examTable,
      secTitle("三", "本周汇总"),
      sumTable,
      note("每周封顶：4 小时（超出部分不累计，可换小奖品）。"),
      secTitle("四", "屏幕银行"),
      bankTable,
      secTitle("五", "使用规则提醒"),
      ruleTable,
      secTitle("六", "本周一句话总结"),
      blankLine("做得好的："),
      blankLine("下周改进："),
      signLine,
    ]
  }]
});

Packer.toBuffer(doc).then(buf => {
  fs.writeFileSync("每周屏幕时间记录表.docx", buf);
  console.log("OK");
});



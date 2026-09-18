package com.cinequiz

// Self contained quiz UI. No external assets so it can load straight from a
// data url inside the plugin dex.
object QuizHtml {

    val html: String = """
<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no">
<style>
* { box-sizing: border-box; margin: 0; padding: 0; -webkit-tap-highlight-color: transparent; }
body { font-family: 'Segoe UI', Roboto, sans-serif; background: #0b0d14; color: #eee; height: 100vh; overflow: hidden; display: flex; flex-direction: column; }
#top { padding: 14px 18px 6px; display: flex; justify-content: space-between; align-items: center; }
#round { font-size: 13px; color: #7f8bb0; letter-spacing: 1px; }
#close { color: #7f8bb0; font-size: 22px; padding: 0 6px; cursor: pointer; }
#category { padding: 2px 18px; font-size: 13px; color: #ffb74d; text-transform: uppercase; letter-spacing: 2px; }
#question { padding: 8px 18px 14px; font-size: 21px; font-weight: 600; line-height: 1.35; min-height: 84px; }
#timerWrap { height: 5px; background: #1b2030; margin: 0 18px 14px; border-radius: 3px; overflow: hidden; }
#timer { height: 100%; width: 100%; background: linear-gradient(90deg, #ff7043, #ffb74d); border-radius: 3px; }
#options { flex: 1; display: flex; flex-direction: column; gap: 10px; padding: 0 18px; overflow-y: auto; }
.opt { background: #161b29; border: 1px solid #232b41; border-radius: 14px; padding: 15px 16px; font-size: 16px; color: #e6e9f2; text-align: left; cursor: pointer; transition: transform .06s; }
.opt:active { transform: scale(.98); }
.opt.picked { border-color: #ffb74d; background: #23200f; }
.opt.correct { border-color: #66bb6a; background: #10240f; }
.opt.wrong { border-color: #ef5350; background: #2a1110; opacity: .85; }
.opt.dim { opacity: .45; }
#foot { padding: 12px 18px 16px; font-size: 13px; color: #7f8bb0; min-height: 44px; }
#board { flex: 1; overflow-y: auto; padding: 10px 18px; }
.row { display: flex; justify-content: space-between; padding: 11px 4px; border-bottom: 1px solid #1b2030; font-size: 16px; }
.row b { color: #ffb74d; }
.medal { margin-right: 8px; }
#big { text-align: center; padding: 40px 24px 10px; font-size: 30px; font-weight: 700; }
#sub { text-align: center; color: #7f8bb0; padding: 0 24px 20px; font-size: 15px; }
button { margin: 8px 18px; padding: 14px; border-radius: 14px; border: 0; background: #ffb74d; color: #1a1408; font-size: 16px; font-weight: 700; cursor: pointer; }
.hidden { display: none !important; }
</style>
</head>
<body>
<div id="top"><div id="round"></div><div id="close" onclick="quit()">&#10005;</div></div>
<div id="category"></div>
<div id="question"></div>
<div id="timerWrap" class="hidden"><div id="timer"></div></div>
<div id="options"></div>
<div id="board" class="hidden"></div>
<div id="big" class="hidden"></div>
<div id="sub" class="hidden"></div>
<div id="foot"></div>
<button id="again" class="hidden" onclick="AndroidApp.onRestart && AndroidApp.onRestart()">Play again</button>
<script>
let timerRAF = 0, chosen = -1, phase = '';

function quit() { AndroidApp.onClose(); }

function clearScreen() {
    cancelAnimationFrame(timerRAF);
    document.getElementById('options').className = 'hidden';
    document.getElementById('board').className = 'hidden';
    document.getElementById('big').className = 'hidden';
    document.getElementById('sub').className = 'hidden';
    document.getElementById('again').className = 'hidden';
    document.getElementById('timerWrap').className = 'hidden';
}

function renderQuestion(s) {
    clearScreen();
    phase = 'question'; chosen = -1;
    document.getElementById('options').className = '';
    document.getElementById('timerWrap').className = '';
    document.getElementById('round').textContent = 'ROUND ' + s.round + ' / ' + s.total;
    document.getElementById('category').textContent = s.category;
    document.getElementById('question').textContent = s.question;
    document.getElementById('foot').textContent = 'Tap an answer, faster means more points';
    const wrap = document.getElementById('options');
    wrap.innerHTML = '';
    s.options.forEach(function (opt, i) {
        const btn = document.createElement('div');
        btn.className = 'opt';
        btn.textContent = String.fromCharCode(65 + i) + '.  ' + opt;
        btn.onclick = function () { if (chosen === -1) { chosen = i; btn.classList.add('picked'); AndroidApp.onAnswer(i); } };
        wrap.appendChild(btn);
    });
    tickTimer(s.deadline);
}

function tickTimer(deadline) {
    const bar = document.getElementById('timer');
    function step() {
        const left = Math.max(0, deadline - Date.now());
        const pct = Math.max(0, Math.min(100, left / 12000 * 100));
        bar.style.width = pct + '%';
        if (pct > 0 && phase === 'question') timerRAF = requestAnimationFrame(step);
    }
    step();
}

function renderReveal(s) {
    phase = 'reveal';
    document.getElementById('timerWrap').className = 'hidden';
    const opts = document.querySelectorAll('.opt');
    opts.forEach(function (el, i) {
        if (i === s.correct) el.className = 'opt correct';
        else if (i === chosen) el.className = 'opt wrong';
        else el.className = 'opt dim';
    });
    const names = Object.keys(s.picked || {});
    const bits = names.map(function (n) {
        return n + (s.picked[n] === s.correct ? ' got it' : ' missed');
    });
    document.getElementById('foot').textContent = bits.length ? bits.join('   -   ') : 'Nobody answered in time';
    if (chosen === s.correct) document.getElementById('foot').textContent += '   -   Correct!';
}

function renderBoard(scores) {
    clearScreen();
    document.getElementById('board').className = '';
    const board = document.getElementById('board');
    board.innerHTML = '';
    const medals = ['1st', '2nd', '3rd'];
    Object.keys(scores).sort(function (a, b) { return scores[b] - scores[a]; }).forEach(function (name, i) {
        const row = document.createElement('div');
        row.className = 'row';
        const left = document.createElement('span');
        left.textContent = (medals[i] || (i + 1) + 'th') + '   ' + name;
        const right = document.createElement('b');
        right.textContent = scores[name];
        row.appendChild(left); row.appendChild(right);
        board.appendChild(row);
    });
}

function renderEnd(s) {
    renderBoard(s.scores);
    document.getElementById('big').className = '';
    document.getElementById('big').textContent = 'Game over';
    document.getElementById('sub').className = '';
    document.getElementById('sub').textContent = 'Final scores, rematch anytime';
    document.getElementById('again').className = '';
    document.getElementById('foot').textContent = '';
}

window.onState = function (json) {
    const s = JSON.parse(json);
    if (s.type === 'QUIZ_ANSWERED') {
        const foot = document.getElementById('foot');
        foot.textContent = s.name + ' locked an answer';
        return;
    }
    if (s.phase === 'question') renderQuestion(s);
    else if (s.phase === 'reveal') renderReveal(s);
    else if (s.phase === 'end') renderEnd(s);
    else if (s.phase === 'lobby') {
        clearScreen();
        document.getElementById('round').textContent = 'LOBBY';
        document.getElementById('question').textContent = 'Get ready';
        document.getElementById('foot').textContent = s.message || '';
    }
};
</script>
</body>
</html>
"""
}

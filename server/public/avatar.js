class Live2DAdapter {
  constructor() { this.mouth = document.querySelector("#mouth"); this.value = 0; }
  setMouth(value) {
    const target = Math.max(0, Math.min(1, Number(value) || 0));
    this.value += (target - this.value) * 0.45;
    this.mouth.style.transform = `scaleY(${1 + this.value * 7})`;
  }
}
window.chihiro = new Live2DAdapter();

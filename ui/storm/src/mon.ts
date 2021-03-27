import { getNow } from 'puz/util';

let newAt = getNow();
const thresholdCentis = 70;

export const newPuzzle = () => {
  newAt = getNow();
};

export const firstMove = (correct: boolean) => {
  if (!correct) return;
  const centis = Math.round((getNow() - newAt) / 20) * 2;
  if (centis <= thresholdCentis) fetch(`/jsmon/storm-first-move:${correct}:${centis}`, { method: 'post' });
};

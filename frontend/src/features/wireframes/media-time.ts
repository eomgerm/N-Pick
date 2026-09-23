export function formatMediaTime(seconds: number) {
  const value = Math.max(0, Math.floor(seconds));
  const hours = Math.floor(value / 3600);
  const minutes = Math.floor((value % 3600) / 60);
  const time = `${String(minutes).padStart(2, '0')}:${String(value % 60).padStart(2, '0')}`;
  return hours ? `${hours}:${time}` : time;
}

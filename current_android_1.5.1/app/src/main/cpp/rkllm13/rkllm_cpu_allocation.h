#pragma once
constexpr bool rkllmDiagnosticCpuPair(int cpus, unsigned mask) {
  return (cpus == 4 && mask == 240u) || (cpus == 2 && mask == 192u);
}

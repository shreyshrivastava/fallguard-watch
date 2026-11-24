#!/usr/bin/env python3
"""Export the trained wrist model for the watch app (PyTorch Mobile lite format).

Writes activity_collector/wear/src/main/assets/wrist_model.ptl (git-ignored:
the model is trained on WEDA-FALL, which has no data license) and checks the
exported model gives the same probabilities as the trained one.

The app bundles pytorch_android_lite 1.13.1, which reads bytecode up to v8, so
the export is backported to v8.

Usage (from the repo root, after train_wrist.py):
  .venv/bin/python har_pipeline/export_wrist.py
"""
import os
import sys

import numpy as np
import torch
from torch.jit.mobile import _backport_for_mobile, _get_model_bytecode_version, _load_for_lite_interpreter

sys.path.insert(0, os.path.dirname(__file__))
from models.neural_net import HARNet  # noqa: E402
from train_wrist import OUT, WIN  # noqa: E402

ASSETS = "activity_collector/wear/src/main/assets"
BYTECODE = 8   # highest version pytorch_android_lite 1.13.1 can load


def main():
    model = HARNet(n_classes=2)
    model.load_state_dict(torch.load(f"{OUT}/wrist_model.pt"))
    model.eval()
    traced = torch.jit.trace(model, torch.zeros(1, WIN, 6))

    os.makedirs(ASSETS, exist_ok=True)
    tmp = f"{OUT}/wrist_model_latest.ptl"
    dst = f"{ASSETS}/wrist_model.ptl"
    traced._save_for_lite_interpreter(tmp)
    if _get_model_bytecode_version(tmp) > BYTECODE:
        assert _backport_for_mobile(tmp, dst, BYTECODE), "backport to bytecode v8 failed"
    else:
        os.replace(tmp, dst)
    assert _get_model_bytecode_version(dst) <= BYTECODE

    # Same answers before and after export, on random and on realistic input.
    lite = _load_for_lite_interpreter(dst)
    rng = np.random.default_rng(0)
    batch = torch.from_numpy(rng.normal(0, 5, (64, WIN, 6)).astype(np.float32))
    batch[:, :, 2] += 9.8
    with torch.no_grad():
        want = torch.softmax(model(batch), 1)
        got = torch.cat([torch.softmax(lite(batch[i:i + 1]), 1) for i in range(len(batch))])
    diff = (want - got).abs().max().item()
    assert diff < 1e-4, f"exported model differs by {diff}"
    with open(f"{ASSETS}/wrist_labels.txt", "w") as f:
        f.write(open(f"{OUT}/wrist_labels.txt").read())
    print(f"Exported {dst} ({os.path.getsize(dst) / 1024:.0f} KB, bytecode v{_get_model_bytecode_version(dst)}), "
          f"max probability difference {diff:.2e}")


if __name__ == "__main__":
    main()

"""設定値・スイッチ・電池プリセット・実容量・パスワード。Kotlin 版 ``Settings.kt`` の移植。"""

from __future__ import annotations

import math
from dataclasses import dataclass
from enum import Enum
from collections.abc import Mapping, Sequence

from ..labels import t


def round_half_up(x: float) -> int:
    """Kotlin の roundToInt() と同じ丸め（0.5 は +∞ 方向）"""
    return math.floor(x + 0.5)


@dataclass(frozen=True)
class Encoding:
    """生の値 ⇄ 表示値の変換。表示値 = (raw − offset) × scale × sign"""

    scale: float
    offset: int = 0
    sign: int = 1

    def decode(self, raw: int) -> float:
        return (raw - self.offset) * self.scale * self.sign

    def encode(self, value: float) -> int:
        return round_half_up(value / (self.scale * self.sign)) + self.offset


RAW = Encoding(1.0)
MILLI = Encoding(0.001)
DECI = Encoding(0.1)
TEMPERATURE = Encoding(1.0, offset=40)
#: 充電電流。この BMS では充電を 30000 より小さい値で表す（書き込み: 30000 − 10×A）
CHARGE_CURRENT = Encoding(0.1, offset=30000, sign=-1)
#: 放電電流。30000 より大きい値で表す（書き込み: 30000 + 10×A）
DISCHARGE_CURRENT = Encoding(0.1, offset=30000)


class SettingGroup(Enum):
    VOLTAGE = "group_voltage"
    CURRENT = "group_current"
    TEMPERATURE = "group_temperature"
    BATTERY = "group_battery"
    BALANCE = "group_balance"
    INFO = "group_info"

    @property
    def label(self) -> str:
        return t(self.value)


class ReleaseRule(Enum):
    """解除値との関係（保護値が解除値をまたがないようにする）"""

    NONE = "none"
    ABOVE_RELEASE = "above_release"
    BELOW_RELEASE = "below_release"


@dataclass(frozen=True)
class Setting:
    """設定項目の定義。範囲（min〜max）は元アプリには無く、誤設定を防ぐために BMS Japan で決めたもの。"""

    key: str
    register: int
    label_key: str
    group: SettingGroup
    encoding: Encoding
    decimals: int
    unit: str
    min: float
    max: float
    release_register: int | None = None
    release_rule: ReleaseRule = ReleaseRule.NONE
    editable: bool = True

    @property
    def label(self) -> str:
        return t(self.label_key)

    def format(self, value: float) -> str:
        return f"{value:.{self.decimals}f}"


V, A, C = SettingGroup.VOLTAGE, SettingGroup.CURRENT, SettingGroup.TEMPERATURE
ABOVE, BELOW = ReleaseRule.ABOVE_RELEASE, ReleaseRule.BELOW_RELEASE

SETTINGS: list[Setting] = [
    Setting("cell_ov", 140, "set_cell_ov", V, MILLI, 3, "V", 2.0, 4.5, 139, ABOVE),
    Setting("cell_uv", 142, "set_cell_uv", V, MILLI, 3, "V", 1.5, 3.5, 141, BELOW),
    Setting("pack_ov", 144, "set_pack_ov", V, DECI, 1, "V", 1.0, 300.0, 143, ABOVE),
    Setting("pack_uv", 146, "set_pack_uv", V, DECI, 1, "V", 1.0, 300.0, 145, BELOW),
    Setting("cell_diff", 160, "set_cell_diff", V, MILLI, 3, "V", 0.010, 1.000, 159, ABOVE),
    Setting("charge_oc", 148, "set_charge_oc", A, CHARGE_CURRENT, 1, "A", 1.0, 500.0, 147),
    Setting("discharge_oc", 150, "set_discharge_oc", A, DISCHARGE_CURRENT, 1, "A", 1.0, 500.0, 149),
    Setting("charge_ot", 152, "set_charge_ot", C, TEMPERATURE, 0, "℃", 0.0, 100.0, 151, ABOVE),
    Setting("charge_ut", 154, "set_charge_ut", C, TEMPERATURE, 0, "℃", -40.0, 30.0, 153, BELOW),
    Setting("discharge_ot", 156, "set_discharge_ot", C, TEMPERATURE, 0, "℃", 0.0, 100.0, 155, ABOVE),
    Setting("discharge_ut", 158, "set_discharge_ut", C, TEMPERATURE, 0, "℃", -40.0, 30.0, 157, BELOW),
    Setting("temp_diff", 162, "set_temp_diff", C, RAW, 0, "℃", 1.0, 50.0, 161, ABOVE),
    Setting("mos_ot", 168, "set_mos_ot", C, TEMPERATURE, 0, "℃", 30.0, 120.0),
    Setting("rated_capacity", 128, "set_rated_capacity", SettingGroup.BATTERY, DECI, 1, "Ah", 1.0, 2000.0),
    Setting("cell_nominal", 129, "set_cell_nominal", SettingGroup.BATTERY, MILLI, 3, "V", 1.5, 4.5),
    Setting("soc", 167, "set_soc", SettingGroup.BATTERY, DECI, 1, "%", 0.0, 100.0),
    Setting("sleep_wait", 138, "set_sleep_wait", SettingGroup.BATTERY, RAW, 0, "秒", 0.0, 65535.0),
    Setting("balance_start_v", 163, "set_balance_start_v", SettingGroup.BALANCE, MILLI, 3, "V", 2.0, 4.5),
    Setting("balance_start_diff", 164, "set_balance_start_diff", SettingGroup.BALANCE, MILLI, 3, "V", 0.001, 0.500),
    Setting("balance_current", 208, "set_balance_current", SettingGroup.BALANCE, DECI, 1, "A", 0.1, 20.0),
    # 機器の構成に関わる値は、誤って変えると計測が狂うため表示だけにする
    Setting("board1_cells", 131, "set_board1_cells", SettingGroup.INFO, RAW, 0, "", 0.0, 0.0, editable=False),
    Setting("board2_cells", 132, "set_board2_cells", SettingGroup.INFO, RAW, 0, "", 0.0, 0.0, editable=False),
    Setting("board3_cells", 133, "set_board3_cells", SettingGroup.INFO, RAW, 0, "", 0.0, 0.0, editable=False),
    Setting("temp_sensors", 134, "set_temp_sensors", SettingGroup.INFO, RAW, 0, "", 0.0, 0.0, editable=False),
]
SETTINGS_BY_KEY: dict[str, Setting] = {s.key: s for s in SETTINGS}


class SwitchSetting(Enum):
    """ON/OFF で切り替える項目。値 = (レジスタ, ラベル, ON 確認文, OFF 確認文)"""

    CHARGE_MOS = (165, "label_charge_mos", "confirm_charge_on", "confirm_charge_off")
    DISCHARGE_MOS = (166, "label_discharge_mos", "confirm_discharge_on", "confirm_discharge_off")
    BALANCE = (207, "label_balancing", "confirm_balance_on", "confirm_balance_off")

    @property
    def register(self) -> int:
        return self.value[0]

    @property
    def label(self) -> str:
        return t(self.value[1])

    def confirm_text(self, on: bool) -> str:
        return t(self.value[2] if on else self.value[3])

    @staticmethod
    def from_name(name: str) -> "SwitchSetting":
        n = name.strip().lower()
        aliases = {
            "charge": SwitchSetting.CHARGE_MOS,
            "charge_mos": SwitchSetting.CHARGE_MOS,
            "discharge": SwitchSetting.DISCHARGE_MOS,
            "discharge_mos": SwitchSetting.DISCHARGE_MOS,
            "balance": SwitchSetting.BALANCE,
            "balancing": SwitchSetting.BALANCE,
        }
        if n not in aliases:
            raise ValueError(f"unknown switch: {name} (charge / discharge / balance)")
        return aliases[n]


class BatteryType(Enum):
    """値 = (コード, ラベル, セル過電圧解除, 保護, セル低電圧解除, 保護)"""

    LFP = (0, "battery_lfp", 3.55, 3.60, 2.65, 2.60)
    NCM = (1, "battery_ncm", 4.10, 4.20, 2.85, 2.82)
    LTO = (2, "battery_lto", 2.65, 2.70, 1.85, 1.80)

    @property
    def code(self) -> int:
        return self.value[0]

    @property
    def label(self) -> str:
        return t(self.value[1])

    @property
    def cell_ov_release(self) -> float:
        return self.value[2]

    @property
    def cell_ov(self) -> float:
        return self.value[3]

    @property
    def cell_uv_release(self) -> float:
        return self.value[4]

    @property
    def cell_uv(self) -> float:
        return self.value[5]

    def available_for(self, cell_count: int) -> bool:
        """元アプリと同じ条件: 20 セルを超えると三元系、6 セル未満だとチタン酸は選べない"""
        if self is BatteryType.NCM:
            return cell_count <= 20
        if self is BatteryType.LTO:
            return cell_count >= 6
        return True

    @staticmethod
    def from_code(code: int | None) -> "BatteryType | None":
        for b in BatteryType:
            if b.code == code:
                return b
        return None

    @staticmethod
    def from_name(name: str) -> "BatteryType":
        try:
            return BatteryType[name.strip().upper()]
        except KeyError:
            raise ValueError(f"unknown battery type: {name} (lfp / ncm / lto)") from None


MAIN_START = 128
MAIN_COUNT = 41  # 128〜168
BALANCE_START = 204
BALANCE_COUNT = 5  # 204〜208


@dataclass(frozen=True)
class BmsSettings:
    """読み出した設定値（レジスタ番号 → 生の値）"""

    raw: Mapping[int, int]

    def get(self, s: Setting) -> float | None:
        v = self.raw.get(s.register)
        return None if v is None else s.encoding.decode(v)

    def release(self, s: Setting) -> float | None:
        if s.release_register is None:
            return None
        v = self.raw.get(s.release_register)
        return None if v is None else s.encoding.decode(v)

    def switch(self, s: SwitchSetting) -> bool | None:
        v = self.raw.get(s.register)
        return None if v is None else v != 0

    @property
    def battery_type(self) -> BatteryType | None:
        return BatteryType.from_code(self.raw.get(137))

    def merge(self, start: int, regs: Sequence[int]) -> "BmsSettings":
        d = dict(self.raw)
        for i, v in enumerate(regs):
            d[start + i] = v
        return BmsSettings(d)


EMPTY_SETTINGS = BmsSettings({})


# ---- 入力チェック ----


@dataclass(frozen=True)
class ValidationResult:
    """kind: ok / not_a_number / out_of_range / must_be_above / must_be_below / not_editable"""

    kind: str
    raw: int | None = None
    min: str | None = None
    max: str | None = None
    release: str | None = None

    @property
    def ok(self) -> bool:
        return self.kind == "ok"

    def message(self, s: Setting) -> str:
        if self.kind == "ok":
            return "OK"
        if self.kind == "not_a_number":
            return "数値を入力してください"
        if self.kind == "out_of_range":
            return f"{self.min}〜{self.max} {s.unit} の範囲で入力してください"
        if self.kind == "must_be_above":
            return f"保護値は解除値（{self.release} {s.unit}）より大きくしてください"
        if self.kind == "must_be_below":
            return f"保護値は解除値（{self.release} {s.unit}）より小さくしてください"
        if self.kind == "not_editable":
            return "この項目は表示のみで、変更できません"
        return self.kind


def parse_number(text: str | float | int) -> float | None:
    if isinstance(text, (int, float)) and not isinstance(text, bool):
        v = float(text)
    else:
        try:
            v = float(str(text).strip().replace(",", "."))
        except ValueError:
            return None
    if math.isnan(v) or math.isinf(v):
        return None
    return v


def validate(setting: Setting, value: str | float | int, current: BmsSettings) -> ValidationResult:
    """Kotlin の Setting.validate() と同じ確認（範囲・解除値との関係・16bit に収まるか）"""
    if not setting.editable:
        return ValidationResult("not_editable")
    v = parse_number(value)
    if v is None:
        return ValidationResult("not_a_number")
    fmin, fmax = setting.format(setting.min), setting.format(setting.max)
    if v < setting.min - 1e-9 or v > setting.max + 1e-9:
        return ValidationResult("out_of_range", min=fmin, max=fmax)
    release = current.release(setting)
    if release is not None:
        if setting.release_rule is ReleaseRule.ABOVE_RELEASE and v <= release:
            return ValidationResult("must_be_above", release=setting.format(release))
        if setting.release_rule is ReleaseRule.BELOW_RELEASE and v >= release:
            return ValidationResult("must_be_below", release=setting.format(release))
    raw = setting.encoding.encode(v)
    if not 0 <= raw <= 0xFFFF:
        return ValidationResult("out_of_range", min=fmin, max=fmax)
    return ValidationResult("ok", raw=raw)


# ---- 電池の種類 ----

PRESET_START = 137
PRESET_COUNT = 27


#: 電池の種類のプリセットを書いてよいセル数（この BMS は最大 48 セル）
MIN_PRESET_CELLS = 1
MAX_PRESET_CELLS = 48


def build_battery_preset(btype: BatteryType, cell_count: int, current: BmsSettings) -> list[int] | None:
    """電池の種類を変えるときの一括書き込み（元アプリと同じく 137 から 27 レジスタ）。

    セル過電圧・低電圧・総電圧の保護/解除値と均等化開始電圧をプリセットに置き換え、
    それ以外は今の値をそのまま送り返す。今の値が揃っていなければ None。
    セル数（BMS から読んだ値）が 1〜48 でなければ None（0 だと総電圧の保護値が 0 V になるため）。
    """
    if not MIN_PRESET_CELLS <= cell_count <= MAX_PRESET_CELLS:
        return None
    if not btype.available_for(cell_count):
        return None
    values: list[int] = []
    for i in range(PRESET_COUNT):
        v = current.raw.get(PRESET_START + i)
        if v is None:
            return None
        values.append(v)

    def put(reg: int, v: int) -> None:
        values[reg - PRESET_START] = v

    def mv(v: float) -> int:
        return round_half_up(v * 1000)

    def pack_deci(per_cell: float) -> int:
        return round_half_up(per_cell * cell_count * 10)

    put(137, btype.code)
    put(139, mv(btype.cell_ov_release))
    put(140, mv(btype.cell_ov))
    put(141, mv(btype.cell_uv_release))
    put(142, mv(btype.cell_uv))
    put(143, pack_deci(btype.cell_ov_release))
    put(144, pack_deci(btype.cell_ov))
    put(145, pack_deci(btype.cell_uv_release))
    put(146, pack_deci(btype.cell_uv))
    put(163, 3000)
    return values


# ---- 実容量（スレーブ 0x81 のレジスタ 267〜268、32bit、mAh） ----


class ActualCapacity:
    REGISTER = 267
    COUNT = 2
    MIN_AH = 1.0
    MAX_AH = 2000.0

    @staticmethod
    def decode(regs: Sequence[int]) -> int:
        return (regs[0] << 16) | regs[1]

    @staticmethod
    def encode(mah: int) -> list[int]:
        return [(mah >> 16) & 0xFFFF, mah & 0xFFFF]

    @staticmethod
    def format(mah: int) -> str:
        return f"{mah / 1000.0:.1f}"

    @staticmethod
    def parse(text: str | float | int) -> int | None:
        """入力（Ah）を確認して mAh を返す。だめなら None"""
        ah = parse_number(text)
        if ah is None:
            return None
        if ah < ActualCapacity.MIN_AH - 1e-9 or ah > ActualCapacity.MAX_AH + 1e-9:
            return None
        return round_half_up(ah * 1000)


# ---- パスワード（レジスタ 201〜203、ASCII 6 文字） ----


class PasswordChangeCheck(Enum):
    OK = "ok"
    EMPTY = "empty"
    WRONG_OLD = "wrong_old"
    BAD_FORMAT = "bad_format"
    MISMATCH = "mismatch"
    SAME_AS_OLD = "same_as_old"

    @property
    def message(self) -> str:
        return {
            "ok": "OK",
            "empty": "すべての欄を入力してください",
            "wrong_old": "今のパスワードが違います",
            "bad_format": "新しいパスワードは 6 桁の数字にしてください",
            "mismatch": "確認用のパスワードが一致しません",
            "same_as_old": "新しいパスワードが今と同じです",
        }[self.value]


class BmsPassword:
    START = 201
    COUNT = 3
    DEFAULT = "123456"

    @staticmethod
    def decode(regs: Sequence[int]) -> str:
        chars = []
        for r in regs:
            chars.append(chr((r >> 8) & 0xFF))
            chars.append(chr(r & 0xFF))
        return "".join(chars).rstrip("\x00 ")

    @staticmethod
    def is_valid_format(pwd: str) -> bool:
        """6 桁未満だと 3 レジスタ分のデータが足りないフレームになるため、6 桁ちょうどにする"""
        return len(pwd) == BmsPassword.COUNT * 2 and all("0" <= c <= "9" for c in pwd)

    @staticmethod
    def encode(pwd: str) -> list[int]:
        if not BmsPassword.is_valid_format(pwd):
            raise ValueError("password must be 6 digits")
        return [(ord(pwd[i * 2]) << 8) | ord(pwd[i * 2 + 1]) for i in range(BmsPassword.COUNT)]

    @staticmethod
    def validate_change(current: str | None, old: str, new: str, repeat: str) -> PasswordChangeCheck:
        if not old or not new or not repeat:
            return PasswordChangeCheck.EMPTY
        if current is None or old != current:
            return PasswordChangeCheck.WRONG_OLD
        if not BmsPassword.is_valid_format(new):
            return PasswordChangeCheck.BAD_FORMAT
        if new != repeat:
            return PasswordChangeCheck.MISMATCH
        if new == old:
            return PasswordChangeCheck.SAME_AS_OLD
        return PasswordChangeCheck.OK

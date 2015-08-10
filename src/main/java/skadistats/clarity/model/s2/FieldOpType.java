package skadistats.clarity.model.s2;

import skadistats.clarity.decoder.BitStream;

public enum FieldOpType {

    PlusOne(36271, "0") {
        @Override
        public void execute(FieldPath fp, BitStream bs) {
            fp.path[fp.last] += 1;
        }
    },
    PlusTwo(10334, "1110") {
        @Override
        public void execute(FieldPath fp, BitStream bs) {
            fp.path[fp.last] += 2;
        }
    },
    PlusThree(1375, "110010") {
        @Override
        public void execute(FieldPath fp, BitStream bs) {
            fp.path[fp.last] += 3;
        }
    },
    PlusFour(646, "11011111") {
        @Override
        public void execute(FieldPath fp, BitStream bs) {
            fp.path[fp.last] += 4;
        }
    },
    PlusN(4128, "11010") {
        @Override
        public void execute(FieldPath fp, BitStream bs) {
            for (int bc : BIT_COUNTS) {
                if (bs.readNumericBits(1) == 1) {
                    fp.path[fp.last] += bs.readNumericBits(bc) + 5;
                }
            }
        }
    },
    PushOneLeftDeltaZeroRightZero(35, "110110001101") {
        @Override
        public void execute(FieldPath fp, BitStream bs) {
            fp.path[++fp.last] = 0;
        }
    },
    PushOneLeftDeltaZeroRightNonZero(3) {
        @Override
        public void execute(FieldPath fp, BitStream bs) {
            for (int bc : BIT_COUNTS) {
                if (bs.readNumericBits(1) == 1) {
                    fp.path[++fp.last] = bs.readNumericBits(bc);
                    return;
                }
            }
        }
    },
    PushOneLeftDeltaOneRightZero(521, "11011010") {
        @Override
        public void execute(FieldPath fp, BitStream bs) {
            fp.path[fp.last]++;
            fp.path[++fp.last] = 0;
        }
    },
    PushOneLeftDeltaOneRightNonZero(2942, "11000") {
        @Override
        public void execute(FieldPath fp, BitStream bs) {
            fp.path[fp.last]++;
            for (int bc : BIT_COUNTS) {
                if (bs.readNumericBits(1) == 1) {
                    fp.path[++fp.last] = bs.readNumericBits(bc);
                    return;
                }
            }
        }
    },
    PushOneLeftDeltaNRightZero(560),
    PushOneLeftDeltaNRightNonZero(471),
    PushOneLeftDeltaNRightNonZeroPack6Bits(10530),
    PushOneLeftDeltaNRightNonZeroPack8Bits(251),
    PushTwoLeftDeltaZero(0, "110110001100100110000000000") {
        @Override
        public void execute(FieldPath fp, BitStream bs) {
            fp.path[++fp.last] = 0;
            fp.path[++fp.last] = 0;
        }
    },
    PushTwoPack5LeftDeltaZero(0),
    PushThreeLeftDeltaZero(0),
    PushThreePack5LeftDeltaZero(0),
    PushTwoLeftDeltaOne(0),
    PushTwoPack5LeftDeltaOne(0),
    PushThreeLeftDeltaOne(0),
    PushThreePack5LeftDeltaOne(0),
    PushTwoLeftDeltaN(0),
    PushTwoPack5LeftDeltaN(0),
    PushThreeLeftDeltaN(0),
    PushThreePack5LeftDeltaN(0),
    PushN(0),
    PushNAndNonTopographical(310),
    PopOnePlusOne(2, "110110001100001") {
        @Override
        public void execute(FieldPath fp, BitStream bs) {
            fp.path[--fp.last]++;
        }
    },
    PopOnePlusN(0),
    PopAllButOnePlusOne(1837, "110011") {
        @Override
        public void execute(FieldPath fp, BitStream bs) {
            fp.last = 0;
            fp.path[0]++;
        }
    },
    PopAllButOnePlusN(149),
    PopAllButOnePlusNPack3Bits(300),
    PopAllButOnePlusNPack6Bits(634),
    PopNPlusOne(0, "1101100011000110") {
        @Override
        public void execute(FieldPath fp, BitStream bs) {
            for (int bc : BIT_COUNTS) {
                if (bs.readNumericBits(1) == 1) {
                    fp.last -= bs.readNumericBits(bc);
                    fp.path[fp.last]++;
                    return;
                }
            }
        }
    },
    PopNPlusN(0),
    PopNAndNonTopographical(1),
    NonTopoComplex(76, "11011000111") {
        @Override
        public void execute(FieldPath fp, BitStream bs) {
            for (int i = 0; i <= fp.last; i++) {
                if (bs.readNumericBits(1) == 1) {
                    // TODO: THIS IS BUGGY, need to read as signed
                    fp.path[i] += bs.readVarInt();
                }
            }
        }
    },
    NonTopoPenultimatePluseOne(271),
    NonTopoComplexPack4Bits(99, "1101100010") {
        @Override
        public void execute(FieldPath fp, BitStream bs) {
            for (int i = 0; i <= fp.last; i++) {
                if (bs.readNumericBits(1) == 1) {
                    fp.path[i] += bs.readNumericBits(4) - 7;
                }
            }
        }
    },
    FieldPathEncodeFinish(25474, "10") {
        @Override
        public void execute(FieldPath fp, BitStream bs) {
        }
    };

    private static final int[] BIT_COUNTS = {2, 4, 10, 17, 30};

    private final int weight;
    private final String prefix;

    FieldOpType(int weight) {
        this(weight, null);
    }

    FieldOpType(int weight, String prefix) {
        this.weight = weight;
        this.prefix = prefix;
    }

    public void execute(FieldPath fp, BitStream bs) {
        throw new UnsupportedOperationException(String.format("FieldOp '%s' not implemented!", this.toString()));
    }

    public int getWeight() {
        return weight;
    }

    public String getPrefix() {
        return prefix;
    }
}

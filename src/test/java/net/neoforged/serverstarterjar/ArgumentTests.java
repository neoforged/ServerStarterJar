package net.neoforged.serverstarterjar;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class ArgumentTests {
    @Test
    void testEscaping() {
        assertThat(Utils.toArgs("\"some text with escaped \\\" quotes\" @some_arg_file.txt 'some other text'"))
                .containsExactly("some text with escaped \" quotes", "@some_arg_file.txt", "some other text");

        assertThat(Utils.toArgs("\"jre\\21.0.4+7-LTS\\bin\\java.exe\" @user_jvm_args.txt @libraries/net/neoforged/neoforge/21.1.145/win_args.txt nogui %*"))
                .containsExactly(
                        "jre\\21.0.4+7-LTS\\bin\\java.exe",
                        "@user_jvm_args.txt",
                        "@libraries/net/neoforged/neoforge/21.1.145/win_args.txt",
                        "nogui",
                        "%*"
                );
    }
}

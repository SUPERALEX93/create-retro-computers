package io.github.retrocomputers;

import java.util.List;
import java.util.Map;
import net.minecraft.network.chat.Component;

final class ProgramExamples {
    static final Map<String, String> SOURCES = Map.of(
        "hello.py", "name = input('Name: ')\nprint('Hello', name)\n",
        "hello.lua", "io.write('Name: ')\nlocal name = io.read()\nprint('Hello ' .. name)\n",
        "hello.c", "#include <stdio.h>\nint main(void) {\n    char name[64];\n    printf(\"Name: \");\n    if (fgets(name, sizeof(name), stdin)) printf(\"Hello %s\", name);\n    return 0;\n}\n",
        "hello.cpp", "#include <iostream>\n#include <string>\nint main() {\n    std::string name;\n    std::cout << \"Name: \";\n    std::getline(std::cin, name);\n    std::cout << \"Hello \" << name << std::endl;\n}\n",
        "hello.cs", "using System;\nclass Program {\n    static void Main() {\n        Console.Write(\"Name: \");\n        string name = Console.ReadLine();\n        Console.WriteLine(\"Hello \" + name);\n    }\n}\n",
        "main.java", "import java.util.Scanner;\npublic class Main {\n    public static void main(String[] args) {\n        System.out.print(\"Name: \");\n        String name = new Scanner(System.in).nextLine();\n        System.out.println(\"Hello \" + name);\n    }\n}\n",
        "hello.rs", "use std::io::{self, Write};\nfn main() {\n    print!(\"Name: \");\n    io::stdout().flush().unwrap();\n    let mut name = String::new();\n    io::stdin().read_line(&mut name).unwrap();\n    println!(\"Hello {}\", name.trim());\n}\n"
    );

    static List<Component> install(Device owner) throws java.io.IOException {
        if (!owner.files.fileExists("/examples")) owner.files.mkdir("/examples", owner.diskCapacity());
        for (var example : SOURCES.entrySet()) {
            String path = "/examples/" + example.getKey();
            if (!owner.files.fileExists(path)) owner.files.writeText(path, example.getValue(), owner.diskCapacity());
        }
        return List.of(message("installed"));
    }

    static Component message(String key, Object... args) {
        return Component.translatable("retrocomputers.languages." + key, args);
    }
}

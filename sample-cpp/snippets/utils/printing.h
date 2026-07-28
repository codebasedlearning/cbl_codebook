// (C) A.Voß, a.voss@fh-aachen.de, info@codebasedlearning.dev

#pragma once

#include <iostream>
#include <string>
#include <string_view>

/*  Prints the section header the Codebook plugin looks for in the Run console:
    a name line underlined with '=' (see output.section.regex in cbl.properties).

    Call it as the first statement of a demo function and pass __func__, so the
    name is never written twice:

        void explaining_something() {
            print_function_header(__func__);

    C++ has no decorators, so this is the closest counterpart to the Python
    sample's @print_function_header - a call instead of a wrapper.  */
inline void print_function_header(std::string_view name) {
    std::cout << "\n" << name << "\n" << std::string(name.size(), '=') << std::endl;
}

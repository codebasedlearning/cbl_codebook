// (C) A.Voß – codebasedlearning.dev – Study Maths and Computer Science (AMI) with us @ FH Aachen – https://ami.codebasedlearning.dev

/* ---- Content ----
 *
 * Teaching Focus
 * - ...
 *
 * Background
 * - ...
 *
 * See also
 * - !![#...]
 */

/* ---- Header ---- */

#include <cstdlib>
#include <iostream>

#include "utils/printing.h"
using std::cout, std::endl;

/* ---- Discussion ---- */

/* --- `a_function` ---
 * Idea
 * - ...
 */
void a_function() {
    print_function_header(__func__);

    /* -- .show ... -- */
    cout << " 1| some text " << endl;
}

/* ---- Run ---- */

int main() {
    a_function();
    // ...

    return EXIT_SUCCESS;
}

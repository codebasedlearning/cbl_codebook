# (C) A.Voß – codebasedlearning.dev – Study Maths and Computer Science (AMI) with us @ FH Aachen – https://ami.codebasedlearning.dev

import functools


def print_function_header(func):
    @functools.wraps(func)
    def wrapper(*args, **kwargs):
        name = func.__name__
        print(f"\n{name}\n{'=' * len(name)}")
        return func(*args, **kwargs)
    return wrapper

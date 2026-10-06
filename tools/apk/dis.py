"""Compatibility shim for callers loading this historical path explicitly.

Use `import dalvik_dis` in Python code: `import dis` may silently get the stdlib module if
another dependency imported it first.
"""
from dalvik_dis import *  # noqa: F401,F403

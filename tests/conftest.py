import importlib.util
from pathlib import Path
import pytest

@pytest.fixture
def data():
    path=Path(__file__).parents[1]/"examples"/"create_demo.py"
    spec=importlib.util.spec_from_file_location("demo_maker",str(path))
    module=importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module.demo()

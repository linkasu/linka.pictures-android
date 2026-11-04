package su.linka.pictures

import java.io.IOException

class SetAlreadyExistsException(val setName: String) : IOException("Set already exists: $setName")

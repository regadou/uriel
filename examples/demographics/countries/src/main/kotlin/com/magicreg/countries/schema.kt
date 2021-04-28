package com.magicreg.countries

import java.io.*
import java.net.*
import java.sql.*
import javax.persistence.*

@Entity @Table(name="language")
data class Language(
  @Id @Column(name="code", length=2) var code: String = "",
  @Column(name="name", length=50) var name: String = "",
  @Column(name="family", length=100) var family: String = "",
): Serializable { }


@Entity @Table(name="continent")
data class Continent(
  @Id @Column(name="name", length=20) var name: String = "",
): Serializable { }


@Entity @Table(name="country")
data class Country(
  @Id @Column(name="code", length=3) var code: String = "",
  @Column(name="code2", length=2) var code2: String = "",
  @Column(name="name", length=60) var name: String = "",
  @Column(name="region", length=60) var region: String = "",
  @ManyToOne var continent: Continent = Continent(),
  @Column(name="surface_area") var surfaceArea: Number = 0.0,
  @Column(name="independance_year") var independanceYear: Int = 0,
  @Column(name="population") var population: Int = 0,
  @Column(name="life_expectancy") var lifeExpectancy: Number = 0.0,
  @Column(name="gross_national_product") var grossNationalProduct: Number = 0.0,
  @Column(name="government_form", length=50) var governmentForm: String = "",
  @Column(name="head_of_state", length=100) var headOfState: String = "",
  @OneToMany var languages: List<CountryLanguage> = listOf(),
): Serializable { }


@Entity @Table(name="city")
data class City(
  @Id @Column(name="id") var id: Int = 0,
  @Column(name="name", length=50) var name: String = "",
  @ManyToOne var country: Country = Country(),
  @Column(name="region", length=50) var region: String = "",
  @Column(name="population") var population: Int = 0,
  @Column(name="longitude") var longitude: Number = 0.0,
  @Column(name="latitude") var latitude: Number = 0.0,
  @Column(name="altitude") var altitude: Number = 0.0,
  @Column(name="capital") var capital: Boolean = false,
): Serializable { }


@Entity @Table(name="country_language")
data class CountryLanguage(
  @Id @ManyToOne var country: Country = Country(),
  @Column(name="language") var language: String = "",
  @Column(name="official") var official: Boolean = false,
  @Column(name="percentage") var percentage: Number = 0.0,
): Serializable { }


